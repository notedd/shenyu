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

package org.dromara.soul.sync.data.http;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import lombok.SneakyThrows;
import org.dromara.soul.common.dto.ConfigData;
import org.dromara.soul.common.dto.PluginData;
import org.dromara.soul.common.enums.ConfigGroupEnum;
import org.dromara.soul.common.utils.GsonUtils;
import org.dromara.soul.sync.data.api.AuthDataSubscriber;
import org.dromara.soul.sync.data.api.MetaDataSubscriber;
import org.dromara.soul.sync.data.api.PluginDataSubscriber;
import org.dromara.soul.sync.data.http.config.HttpConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import wiremock.org.apache.http.HttpHeaders;
import wiremock.org.apache.http.entity.ContentType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class HttpSyncDataServiceTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort(), false);

    private PluginDataSubscriber pluginDataSubscriber;

    private MetaDataSubscriber metaDataSubscriber;

    private AuthDataSubscriber authDataSubscriber;

    private HttpSyncDataService httpSyncDataService;

    @Before
    public final void before() {
        wireMockRule.stubFor(get(urlPathEqualTo("/configs/fetch"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBody(this.mockConfigsFetchResponseJson())
                        .withStatus(200))
        );
        wireMockRule.stubFor(post(urlPathEqualTo("/configs/listener"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBody(this.mockConfigsListenResponseJson())
                        .withStatus(200))
        );

        HttpConfig httpConfig = new HttpConfig();
        httpConfig.setUrl(this.getMockServerUrl());
        // set http connection timeout
        httpConfig.setConnectionTimeout(3000);
        // set delay time
        httpConfig.setDelayTime(3);
        this.pluginDataSubscriber = mock(PluginDataSubscriber.class);
        this.metaDataSubscriber = mock(MetaDataSubscriber.class);
        this.authDataSubscriber = mock(AuthDataSubscriber.class);
        this.httpSyncDataService = new HttpSyncDataService(httpConfig, pluginDataSubscriber,
                Collections.singletonList(metaDataSubscriber), Collections.singletonList(authDataSubscriber));
    }

    @After
    @SneakyThrows
    public void after() {
        httpSyncDataService.close();
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(httpSyncDataService, "RUNNING");
        assertFalse(running.get());
    }

    @Test
    public void test() {
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(httpSyncDataService, "RUNNING");
        assertTrue(running.get());

        verify(pluginDataSubscriber, atLeastOnce()).refreshPluginDataAll();
        verify(metaDataSubscriber, atLeastOnce()).refresh();
        verify(authDataSubscriber, atLeastOnce()).refresh();
    }

    private String getMockServerUrl() {
        return "http://127.0.0.1:" + wireMockRule.port();
    }

    // mock configs listen api response
    private String mockConfigsListenResponseJson() {
        return "{\"code\":200,\"message\":\"success\",\"data\":[\"PLUGIN\"]}";
    }

    // mock configs fetch api response
    @SneakyThrows
    private String mockConfigsFetchResponseJson() {
        ConfigData emptyData = new ConfigData()
                .setLastModifyTime(System.currentTimeMillis()).setData(Collections.emptyList())
                .setMd5("d751713988987e9331980363e24189cf");
        ConfigData pluginData = new ConfigData()
                .setLastModifyTime(System.currentTimeMillis()).setData(Collections.singletonList(PluginData.builder()
                        .id("9")
                        .name("hystrix")
                        .role(0)
                        .enabled(false)
                        .build()))
                .setMd5("1298d5a533d0f896c60cbeca1ec7b017");
        Map<String, Object> data = new HashMap<>();
        data.put(ConfigGroupEnum.PLUGIN.name(), pluginData);
        data.put(ConfigGroupEnum.META_DATA.name(), emptyData);
        data.put(ConfigGroupEnum.APP_AUTH.name(), emptyData);
        data.put(ConfigGroupEnum.SELECTOR.name(), emptyData);
        data.put(ConfigGroupEnum.RULE.name(), emptyData);
        Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        response.put("code", 200);
        return GsonUtils.getInstance().toJson(response);
    }
}
