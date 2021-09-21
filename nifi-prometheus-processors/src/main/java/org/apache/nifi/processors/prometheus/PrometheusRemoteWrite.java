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
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.protobuf.util.JsonFormat;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.xerial.snappy.SnappyInputStream;
import prometheus.Remote.WriteRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tags({ "prometheus", "metrics", "adapter", "remote write" })
@CapabilityDescription("Listen for incoming samples from Prometheus." +
        " Implements a remote endpoint adapter for writing Prometheus" +
        " samples, primarily intended for metrics long term storage")
@WritesAttributes({@WritesAttribute(attribute = "mime.type", description = "This is always application/json.")})
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@TriggerSerially
public class PrometheusRemoteWrite extends AbstractProcessor {

    public static final PropertyDescriptor REMOTE_WRITE_CONTEXT = new PropertyDescriptor.Builder()
            .name("remote-write-context")
            .displayName("Remote Write URL context")
            .description("The context used in the remote_write url property in Prometheus")
            .required(true)
            .defaultValue("/write")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor REMOTE_WRITE_PORT = new PropertyDescriptor.Builder()
            .name("remote-write-port")
            .displayName("Remote Write URL port")
            .description("The port used in the remote_write url property in Prometheus")
            .required(true)
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor JETTY_MAX_THREADS = new PropertyDescriptor.Builder()
            .name("jetty-max-threads")
            .displayName("Jetty Maximum pool threads")
            .description("The port used in the remote_write url property in Prometheus")
            .required(true)
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    static final PropertyDescriptor RECORD_WRITER = new PropertyDescriptor.Builder()
            .name("record-writer")
            .displayName("Record Writer")
            .description("The Record Writer to use in order to serialize the data before writing to a FlowFile")
            .identifiesControllerService(RecordSetWriterFactory.class)
            .expressionLanguageSupported(false)
            .required(true)
            .build();

    static final PropertyDescriptor RECORD_BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("record-batch-size")
            .displayName("Record Batch Size")
            .description("The maximum number of records (metrics) to write to a single FlowFile.")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(false)
            .defaultValue("1000")
            .required(true)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All content that is received is routed to the 'success' relationship")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("All content that is received is routed to the 'failure' relationship")
            .build();

    private static final RecordSchema RECORD_SCHEMA;
    private static final String FILENAME = "filename";
    private static final String PATH = "path";

    static {
        final List<RecordField> recordFields = new ArrayList<>();
        recordFields.add(new RecordField(FILENAME, RecordFieldType.STRING.getDataType(), false));
        recordFields.add(new RecordField(PATH, RecordFieldType.STRING.getDataType(), false));
        RECORD_SCHEMA = new SimpleRecordSchema(recordFields);
    }

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    public static Server serverEndpoint;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(REMOTE_WRITE_CONTEXT);
        descriptors.add(REMOTE_WRITE_PORT);
        descriptors.add(JETTY_MAX_THREADS);
        descriptors.add(RECORD_WRITER);
        descriptors.add(RECORD_BATCH_SIZE);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
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

    @OnUnscheduled
    public void onUnscheduled() throws Exception {
        getLogger().debug("onUnscheduled called");
        if (serverEndpoint != null) {
            serverEndpoint.stop();
        }
    }

    @OnShutdown
    public void onShutdown() throws Exception {
        getLogger().debug("onShutdown called");
        if (serverEndpoint != null) {
            serverEndpoint.stop();
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final int port = context.getProperty(REMOTE_WRITE_PORT).asInteger();
        final int maxPoolThreads = context.getProperty(JETTY_MAX_THREADS).asInteger();
        final String contextPath = context.getProperty(REMOTE_WRITE_CONTEXT).getValue();
        final int recordBatchSize = context.getProperty(RECORD_BATCH_SIZE).asInteger();
        final RecordSetWriterFactory recordSetWriterFactory = context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);

        /*
        if (context.getProperty(MAX_BATCH_METRICS).isSet()) {
            maxBatch = context.getProperty(MAX_BATCH_METRICS).asInteger();
        } else {
           maxBatch = 0;
        }
         */

        getLogger().debug("onTrigger called");

        // Internal Jetty thread pool tuning
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(maxPoolThreads);
        serverEndpoint = new Server(threadPool);

        ServerConnector connector = new ServerConnector(serverEndpoint);
        connector.setPort(port);
        serverEndpoint.addConnector(connector);

        // Setup only one handler serving one context path
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath(contextPath);
        contextHandler.setAllowNullPathInfo(true);
        contextHandler.setHandler(new PrometheusHandler(context, session, recordBatchSize, recordSetWriterFactory));
        serverEndpoint.setHandler(contextHandler);

        try {
            serverEndpoint.start();
            serverEndpoint.join();
        } catch (Exception e) {
            context.yield();
            e.printStackTrace();
        }
    }

    private class PrometheusHandler extends AbstractHandler {
        private final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer();
        private ProcessSession session;
        private ProcessContext context;
        private FlowFile flowFile;
        private int maxBatch;
        private RecordSetWriterFactory recordSetWriterFactory;
        private List<Metrics> metricList = new ArrayList<>();

        public PrometheusHandler(ProcessContext context, ProcessSession session, int maxBatch, RecordSetWriterFactory recordSetWriterFactory) {
            super();
            this.session = session;
            this.context = context;
            this.maxBatch = maxBatch;
            this.recordSetWriterFactory = recordSetWriterFactory;
        }

        /*
        public void commitFlowFile(FlowFile flowFile, ProcessSession session, HttpServletRequest request){
            session.putAttribute(flowfile, "mime.type","application/json");
            session.transfer(flowfile, REL_SUCCESS);
            session.getProvenanceReporter().receive(flowfile, request.getRequestURI());
            session.commit();
            getLogger().debug("SUCCESS relation FlowFile: {}.", new Object[]{flowfile.getId()});
        }
         */

        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

            // The Prometheus request is a Protocol Buffer message compressed with Snappy
            try (SnappyInputStream is = new SnappyInputStream(baseRequest.getInputStream())) {
                if (is == null) {
                     getLogger().error("InputStream is null");
                }

                ServletOutputStream responseOut = response.getOutputStream();
                response.setStatus(HttpServletResponse.SC_OK);

                // Prometheus sample ready for managing
                WriteRequest writeRequest = WriteRequest.parseFrom(is);

                final RecordSetWriterFactory writerFactory =
                        context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);

                FlowFile flowFile = session.create();
                final WriteResult writeResult;
                try (final OutputStream out = session.write(flowFile);
                     final RecordSetWriter recordSetWriter = writerFactory.createWriter(getLogger(),
                             getRecordSchema(), out, Collections.emptyMap())) {

                    recordSetWriter.beginRecordSet();
                    final Record record = createRecord(writeRequest);
                    record.incorporateSchema(getRecordSchema());
                    recordSetWriter.write(record);

                    writeResult = recordSetWriter.finishRecordSet();
                    recordSetWriter.close();

                    final Map<String, String> attributes = new HashMap<>(writeResult.getAttributes());
                    attributes.put("record.count", String.valueOf(writeResult.getRecordCount()));
                    // TODO: session.putAttribute(flowFile, "mime.type","application/json");
                    //flowFile = session.putAllAttributes(flowFile, attributes);

                    session.transfer(flowFile, REL_SUCCESS);
                    session.getProvenanceReporter().receive(flowFile, request.getRequestURI());
                    session.commit();

                } catch (SchemaNotFoundException e) {
                    e.printStackTrace();
                }

                /*
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

                    if (maxBatch == 0 || maxBatch == 1) {
                        flowfile = session.create();
                        session.write(flowfile, new OutputStreamCallback() {
                            @Override
                            public void process(OutputStream out) throws IOException {
                                out.write(gson.toJson(metrics).getBytes());
                                out.flush();
                                out.close();
                            }
                        });
                        getLogger().debug("Batch mode disabled written FlowFile: {}.", new Object[]{flowfile.getId()});
                        commitFlowFile(flowfile, session, request);
                    } else if (metricList.size() < maxBatch) {
                        metricList.add(metrics);
                    }

                    if (metricList.size() == maxBatch) {
                        flowfile = session.create();
                        session.write(flowfile, new OutputStreamCallback() {
                            @Override
                            public void process(OutputStream out) throws IOException {
                                out.write(gson.toJson(metricList).getBytes());
                                out.flush();
                                out.close();
                            }
                        });
                        getLogger().debug("Batch mode enabled, writing {} metrics in FlowFile {}.",
                                new Object[]{metricList.size(), flowfile.getId()});
                        commitFlowFile(flowfile, session, request);
                        metricList.clear();
                    }
                }
                */

                baseRequest.setHandled(true);

            } catch (IOException e) {
                getLogger().error("Ran into an error while processing {}.", new Object[] {flowFile.getId()}, e);
                session.transfer(flowFile, REL_FAILURE);
                context.yield();
                throw e;
            }
        }
    }

    private RecordSchema getRecordSchema() {
        return RECORD_SCHEMA;
    }

    private Record createRecord(final WriteRequest writeRequest) {
        final Map<String, Object> values = new HashMap<>();
        values.put(FILENAME, "my-filename");
        values.put(PATH, "my-path");

        return new MapRecord(getRecordSchema(), values);
    }

    class BatchMetrics {
        public List<Metrics> metrics;
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