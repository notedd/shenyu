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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.PathNotFoundException;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ApplicationJsonStrategy.
 */
public class JsonOperator implements Operator {

    private static final Logger LOG = LoggerFactory.getLogger(JsonOperator.class);

    private static final List<HttpMessageReader<?>> MESSAGE_READERS = HandlerStrategies.builder().build().messageReaders();

    private static final HttpUtils HTTP_UTILS = new HttpUtils();

    private static final int MAX_CACHE_SIZE = 10000;

    private static final Map<String, CacheItem> ACCESS_TOKEN_CACHE = new LinkedHashMap<String, CacheItem>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, CacheItem> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final String GET_TOKEN_URL = "https://wms.shipout.com/api/wms-user/temu/getAccessTokenByTemuToken";

    private static final String TOKEN_PATH = "$.cwAccessToken";

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

    @Override
    public void operation(final DocumentContext context, final ParamMappingRuleHandle paramMappingRuleHandle) {
        if (!CollectionUtils.isEmpty(paramMappingRuleHandle.getAddParameterKeys())) {
            paramMappingRuleHandle.getAddParameterKeys().forEach(info -> context.put(info.getPath(), info.getKey(), info.getValue()));
        }
        // 替换temu合作仓进来的token
        replaceTemuToken(context);
    }

    private void replaceTemuToken(final DocumentContext context) {
        try {
            String cwAccessToken = context.read(TOKEN_PATH);
            if (cwAccessToken == null || cwAccessToken.isEmpty()) {
                LOG.info("context has no cwAccessToken");
                return;
            }

            LOG.info("context has cwAccessToken");

            CacheItem cachedToken = ACCESS_TOKEN_CACHE.get(cwAccessToken);
            if (cachedToken != null) {
                context.set(TOKEN_PATH, cachedToken.token);
                LOG.info("Retrieved cwAccessToken from cache: {}", cachedToken.token);
                return;
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("cwAccessToken", cwAccessToken);
            String result = HTTP_UTILS.request(GET_TOKEN_URL, form, null, HttpUtils.HTTPMethod.POST);

            if (result == null || result.isEmpty()) {
                LOG.error("requestToken result is empty");
                return;
            }

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(result);
            String resultValue = jsonNode.get("result").asText();

            if (!"OK".equals(resultValue)) {
                LOG.error("requestToken result is not OK {}", result);
                return;
            }

            String accessToken = jsonNode.get("data").get("accessToken").asText();
            if (accessToken == null || accessToken.isEmpty() || "null".equals(accessToken)) {
                LOG.error("requestToken result accessToken is empty or null");
                return;
            }

            ACCESS_TOKEN_CACHE.put(cwAccessToken, new CacheItem(accessToken));
            LOG.info("requestToken result: {}", accessToken);
            context.set(TOKEN_PATH, accessToken);
            LOG.info("context set cwAccessToken success {}", context.jsonString());

        } catch (PathNotFoundException e) {
            LOG.info("context no cwAccessToken");
        } catch (Exception e) {
            LOG.error("replaceTemuToken error: {}", e);
        }
    }

    private static class CacheItem {
        private String token;

        CacheItem(final String token) {
            this.token = token;
        }
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

}
