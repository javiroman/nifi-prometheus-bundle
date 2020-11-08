package org.apache.nifi.processors.prometheus;

import org.xerial.snappy.Snappy;
import prometheus.Remote;
import prometheus.Types;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PrometheusMessage {
    public byte[] getSingleMessage() throws IOException {
        Remote.WriteRequest.Builder writeRequestBuilder = Remote.WriteRequest.newBuilder();

        List<Types.Label> labelsList = new ArrayList<>();
        List<Types.Sample> sampleList = new ArrayList<>();
        Types.TimeSeries.Builder timeSeriesBuilder = Types.TimeSeries.newBuilder();
        Types.Label.Builder labelBuilder = Types.Label.newBuilder();
        Types.Sample.Builder sampleBuilder = Types.Sample.newBuilder();

        labelBuilder.setName("name1")
                .setValue("value1");

        Types.Label lbl = labelBuilder.build();
        labelsList.add(lbl);

        labelBuilder.setName("name2")
                .setValue("value2");

        lbl = labelBuilder.build();
        labelsList.add(lbl);

        sampleBuilder.setValue(1)
                .setTimestamp(1111111111111L);

        Types.Sample smpl = sampleBuilder.build();
        sampleList.add(smpl);

        timeSeriesBuilder.addAllLabels(labelsList);
        timeSeriesBuilder.addAllSamples(sampleList);

        Types.TimeSeries t = timeSeriesBuilder.build();
        writeRequestBuilder.addAllTimeseries(Arrays.asList(t));

        Remote.WriteRequest message = writeRequestBuilder.build();

        byte[] compressedMessage = Snappy.compress(message.toByteArray());

        return compressedMessage;
    };

    public byte[] getBatchMessage(String name,
                                  String value,
                                  Double sampleValue,
                                  Long timestamp) throws IOException {
        Remote.WriteRequest.Builder writeRequestBuilder = Remote.WriteRequest.newBuilder();

        List<Types.Label> labelsList = new ArrayList<>();
        List<Types.Sample> sampleList = new ArrayList<>();
        Types.TimeSeries.Builder timeSeriesBuilder = Types.TimeSeries.newBuilder();
        Types.Label.Builder labelBuilder = Types.Label.newBuilder();
        Types.Sample.Builder sampleBuilder = Types.Sample.newBuilder();

        labelBuilder.setName(name)
                .setValue(value);

        Types.Label lbl = labelBuilder.build();
        labelsList.add(lbl);

        sampleBuilder.setValue(sampleValue)
                .setTimestamp(timestamp);

        Types.Sample smpl = sampleBuilder.build();
        sampleList.add(smpl);

        timeSeriesBuilder.addAllLabels(labelsList);
        timeSeriesBuilder.addAllSamples(sampleList);

        Types.TimeSeries t = timeSeriesBuilder.build();
        writeRequestBuilder.addAllTimeseries(Arrays.asList(t));

        Remote.WriteRequest message = writeRequestBuilder.build();

        byte[] compressedMessage = Snappy.compress(message.toByteArray());

        return compressedMessage;
    };
}
