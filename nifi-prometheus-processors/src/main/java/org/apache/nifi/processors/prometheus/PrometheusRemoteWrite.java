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

import org.apache.nifi.annotation.behavior.TriggerSerially;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.xerial.snappy.SnappyInputStream;
import prometheus.Remote.WriteRequest;
import prometheus.Types;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tags({ "prometheus", "metrics", "adapter", "remote write" })
@CapabilityDescription("Listen for incoming samples from Prometheus." +
        " Implements a remote endpoint adapter for writing Prometheus" +
        " samples, primarily intended for metrics long term storage")
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@TriggerSerially
public class PrometheusRemoteWrite extends AbstractProcessor {

    public static final PropertyDescriptor REMOTE_WRITE_CONTEXT = new PropertyDescriptor
            .Builder().name("Remote Write Context")
            .displayName("Remote Write URL context")
            .description("The context used in the remote_write url property in Prometheus")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor REMOTE_WRITE_PORT = new PropertyDescriptor
            .Builder().name("Remote Write Port")
            .displayName("Remote Write URL port")
            .description("The port used in the remote_write url property in Prometheus")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All content that is received is routed to the 'success' relationship")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(REMOTE_WRITE_CONTEXT);
        descriptors.add(REMOTE_WRITE_PORT);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(REL_SUCCESS);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {

    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final int port = context.getProperty(REMOTE_WRITE_PORT).asInteger();
        final String contextPath = context.getProperty(REMOTE_WRITE_CONTEXT).getValue();

        FlowFile flowFile = session.get();
        if ( flowFile == null ) {
            return;
        }
        Server serverEndpoint = new Server(port);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath(contextPath);
        contextHandler.setHandler(new PrometheusHandler(flowFile));
        serverEndpoint.setHandler(contextHandler);

        try {
            serverEndpoint.start();
            serverEndpoint.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class PrometheusHandler extends AbstractHandler {

        private final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer();

        public PrometheusHandler(FlowFile session) {
            super();
            // FIXME
            FlowFile ses = session;
        }

        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException,ServletException {

            // Retrieves the body of the request as binary data: protobuf compressed with snappy.
            try (SnappyInputStream is = new SnappyInputStream(baseRequest.getInputStream())) {

                if (is == null) {
                     getLogger().warn("Inputstream is null");
                }

                ServletOutputStream out = response.getOutputStream();
                response.setStatus(HttpServletResponse.SC_OK);

                WriteRequest writeRequest = WriteRequest.parseFrom(is);

                for (Types.TimeSeries timeSeries: writeRequest.getTimeseriesList()) {
                    List<MetricLabel> labelsList = new ArrayList<>();
                    List<MetricSample> sampleList = new ArrayList<>();
                    Metrics metrics = new Metrics();
                    Gson gson = new Gson();
                    for (Types.Label labelItem: timeSeries.getLabelsList()) {
                        MetricLabel metricsLabel = new MetricLabel();
                        metricsLabel.name = labelItem.getName();
                        metricsLabel.value = labelItem.getValue();
                        labelsList.add(metricsLabel);
                    }
                    for (Types.Sample sample: timeSeries.getSamplesList()) {
                        MetricSample metricSample = new MetricSample();
                        metricSample.sample = Double.toString(sample.getValue());
                        metricSample.timestamp = Long.toString(sample.getTimestamp());
                        sampleList.add(metricSample);
                    }
                    metrics.metricLabels= labelsList;
                    metrics.metricSamples = sampleList;
                    System.out.println(gson.toJson(metrics));
                }

                out.flush();
            } catch (IOException e) {
                throw e;
            }
        }
    }

    class Metrics {
        public List<MetricLabel> metricLabels;
        public List<MetricSample> metricSamples;
    }

    class MetricLabel {
        public String name;
        public String value;
    }

    class MetricSample {
        public String sample;
        public String timestamp;
    }

}