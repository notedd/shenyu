/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.plugin.param.mapping.strategy;

import com.jayway.jsonpath.DocumentContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.shenyu.common.dto.convert.rule.impl.ParamMappingRuleHandle;
import org.apache.shenyu.plugin.api.ShenyuPluginChain;
import org.apache.shenyu.plugin.base.support.BodyInserterContext;
import org.apache.shenyu.plugin.base.support.CachedBodyOutputMessage;
import org.apache.shenyu.plugin.param.mapping.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/**
 * ApplicationJsonStrategy.
 */
public class JsonOperator implements Operator {

    private static final Logger LOG = LoggerFactory.getLogger(JsonOperator.class);

    private static final List<HttpMessageReader<?>> MESSAGE_READERS = HandlerStrategies.builder().build().messageReaders();

    private static final HttpUtils HTTP_UTILS = new HttpUtils();

    @Override
    public Mono<Void> apply(final ServerWebExchange exchange, final ShenyuPluginChain shenyuPluginChain, final ParamMappingRuleHandle paramMappingRuleHandle) {
        ServerRequest serverRequest = ServerRequest.create(exchange, MESSAGE_READERS);
        Mono<String> mono = serverRequest.bodyToMono(String.class).switchIfEmpty(Mono.defer(() -> Mono.just(""))).flatMap(originalBody -> {
            LOG.info("get body data success data:{}", originalBody);
            //process entity
            String modify = operation(originalBody, paramMappingRuleHandle);
            return Mono.just(modify);
        });
        BodyInserter<Mono<String>, ReactiveHttpOutputMessage> bodyInserter = BodyInserters.fromPublisher(mono, String.class);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
        return bodyInserter.insert(outputMessage, new BodyInserterContext())
                .then(Mono.defer(() -> {
                    ServerHttpRequestDecorator decorator = new ModifyServerHttpRequestDecorator(headers, exchange.getRequest(), outputMessage);
                    return shenyuPluginChain.execute(exchange.mutate().request(decorator).build());
                })).onErrorResume((Function<Throwable, Mono<Void>>) throwable -> release(outputMessage, throwable));
    }

    static class ModifyServerHttpRequestDecorator extends ServerHttpRequestDecorator {

        private final HttpHeaders headers;

        private final CachedBodyOutputMessage cachedBodyOutputMessage;

        ModifyServerHttpRequestDecorator(final HttpHeaders headers,
                                         final ServerHttpRequest delegate,
                                         final CachedBodyOutputMessage cachedBodyOutputMessage) {
            super(delegate);
            this.headers = headers;
            this.cachedBodyOutputMessage = cachedBodyOutputMessage;
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public HttpHeaders getHeaders() {
            long contentLength = headers.getContentLength();
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.putAll(headers);
            if (contentLength > 0) {
                httpHeaders.setContentLength(contentLength);
            } else {
                httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            }
            return httpHeaders;
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public Flux<DataBuffer> getBody() {
            return cachedBodyOutputMessage.getBody();
        }
    }

    public void operation(final DocumentContext context, final ParamMappingRuleHandle paramMappingRuleHandle) {
        if (!CollectionUtils.isEmpty(paramMappingRuleHandle.getAddParameterKeys())) {
            paramMappingRuleHandle.getAddParameterKeys().forEach(info -> context.put(info.getPath(), info.getKey(), info.getValue()));
        }
        try {
            // 判断context中是否含有某个key 如果含有则 进行替换
            String cwAccessToken = context.read("$.cwAccessToken");
            if (cwAccessToken != null) {
                LOG.info("context has cwAccessToken");
                try {
                    String result = HTTP_UTILS.get("https://opendev.shipout.com/api/open-api/oms/order/query?orderId=1846092409358094338", null);
                    LOG.info("requestJson result:{}", result);
                    context.set("$.cwAccessToken", "newcwAccessToken");
                } catch (Exception e) {
                    LOG.error("requestJson error:{}", e);
                }
                LOG.info("context set cwAccessToken success {}", context.jsonString());
            }
        } catch (Exception e) {
            LOG.error("json path error:{}", e);
        }
    }
}
