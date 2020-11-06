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
package org.apache.nifi.processors.prometheus;

import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xerial.snappy.Snappy;
import prometheus.Remote;
import prometheus.Types;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class PrometheusRemoteWriteTest {

    private static final String REMOTE_WRITE_CONTEXT = "/test";
    private static final String REMOTE_WRITE_PORT = "2222";
    private TestRunner testRunner;


    @Before
    public void init() {
        testRunner = TestRunners.newTestRunner(PrometheusRemoteWrite.class);
        testRunner.setProperty(PrometheusRemoteWrite.REMOTE_WRITE_CONTEXT, REMOTE_WRITE_CONTEXT);
        testRunner.setProperty(PrometheusRemoteWrite.REMOTE_WRITE_PORT, REMOTE_WRITE_PORT);
        testRunner.run(1, true, true);
    }

    @Test
    public void testProcessor() throws Exception {
        /*
          We have to create Prometheus PB message
          and send compressed with snappy to the
          test server with the real handler:

          List TimeSeries
            List Label
                name
                value
            List Sample:
                value
                timestamp
         */
        Remote.WriteRequest.Builder writeRequestBuilder = Remote.WriteRequest.newBuilder();

        Types.TimeSeries.Builder timeSeriesBuilder = Types.TimeSeries.newBuilder();
        Types.Label.Builder labelBuilder = Types.Label.newBuilder();
        Types.Sample.Builder sampleBuilder = Types.Sample.newBuilder();


        labelBuilder.setName("name1")
                .setValue("value1");

        labelBuilder.setName("name2")
                .setValue("value2");

        Types.Label l = labelBuilder.build();

        sampleBuilder.setValue(1)
                .setTimestamp(1111111111L);

        Types.Sample s = sampleBuilder.build();

        timeSeriesBuilder.addAllLabels(Arrays.asList(l));
        timeSeriesBuilder.addAllSamples(Arrays.asList(s));

        Types.TimeSeries t = timeSeriesBuilder.build();
        writeRequestBuilder.addAllTimeseries(Arrays.asList(t));

        Remote.WriteRequest message = writeRequestBuilder.build();

        byte[] compressedMessage = Snappy.compress(message.toByteArray());

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        ContentResponse response =
                httpClient.newRequest("http://localhost:6666/write")
                        .method(HttpMethod.POST)
                        .content(new InputStreamContentProvider(
                                new ByteArrayInputStream(compressedMessage)))
                        .send();

        assertEquals(response.getStatus(), HttpServletResponse.SC_OK);

        httpClient.stop();
    }

}
