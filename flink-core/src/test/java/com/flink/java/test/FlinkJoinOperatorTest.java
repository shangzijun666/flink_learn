package com.flink.java.test;

import com.flink.common.kafka.KafkaManager;
import com.flink.common.kafka.KafkaManager.*;
import com.func.richfunc.AsyncIODatabaseRequest;
import com.flink.learn.test.common.FlinkJavaStreamTableTestBase;
import com.func.richfunc.AsyncIORichFunction;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatJoinFunction;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.*;
import org.apache.flink.streaming.api.functions.co.*;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class FlinkJoinOperatorTest extends FlinkJavaStreamTableTestBase {

    @Test
    public void windowJoinTest() throws Exception {
        getKafkaKeyStream("test", "localhost:9092", "latest")
                .join(getKafkaKeyStream("test2", "localhost:9092", "latest"))
                .where((KeySelector<KafkaMessge, String>) value -> value.msg())
                .equalTo((KeySelector<KafkaMessge, String>) value -> value.msg())
                .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
                .apply(new FlatJoinFunction<KafkaMessge, KafkaMessge, String>() {
                           @Override
                           public void join(KafkaMessge first, KafkaMessge second, Collector<String> out) throws Exception {
                               out.collect(first.toString() + " <-> " + second.toString());
                           }
                       }
                )
                .print();

        streamEnv.execute("windowJoinTest");
    }


    /**
     * ??????watermark?????????????????????????????????????????????buffer ?????????????????????????????????statettl???????????????oneventtime?????????
     * ?????????connect??????
     *
     * @throws Exception
     */
    @Test
    public void intervalJoinTest() throws Exception {
        // d1: {"ts":15,"msg":"1"}
        // d2 {"ts":25,"msg":"1"} {"ts":5,"msg":"1"} // ????????????
        // d2 : {"ts":26,"msg":"1"} {"ts":4,"msg":"1"}  // join ??????
        getKafkaKeyStream("test", "localhost:9092", "latest")
                .intervalJoin(getKafkaKeyStream("test2", "localhost:9092", "latest"))
                .between(Time.seconds(-10), Time.seconds(10))
                .process(new ProcessJoinFunction<KafkaMessge, KafkaMessge, String>() {
                    @Override
                    public void processElement(KafkaMessge left, KafkaMessge right, Context ctx, Collector<String> out) throws Exception {
                        out.collect(left + " <-> " + right);
                    }
                })
                .print();

        streamEnv.execute("intervalJoinTest");
    }


    /**
     * cd1 ??? cd2 ??????????????????keyby????????????conenct?????????keyby
     *
     * @throws Exception
     */
    @Test
    public void connectTest() throws Exception {
        // {"ts":100,"msg":"268"}
        getKafkaKeyStream("test", "localhost:9092", "latest")
                .connect(getKafkaKeyStream("test2", "localhost:9092", "latest"))
                .keyBy("msg", "msg")
                .process(new CoProcessFunction<KafkaMessge, KafkaMessge, String>() {
                    @Override
                    public void processElement1(KafkaMessge value, Context ctx, Collector<String> out) throws Exception {
                        out.collect(value.toString());
                    }

                    @Override
                    public void processElement2(KafkaMessge value, Context ctx, Collector<String> out) throws Exception {
                        out.collect(value.toString());
                    }
                })
                .returns(Types.STRING)
                .print();
        streamEnv.execute();
    }



    /**
     * ??????io???????????????????????????????????????????????????????????????????????? task????????????????????????asyncInvoke
     * ?????? asyncInvoke(...) ?????????????????????????????????????????????????????????, ????????????????????????????????? I/O???
     * ?????????
     * ??????????????????????????? asyncInvoke(...) ???????????????????????????????????????
     * ?????????????????????????????????????????????????????????????????????????????????????????????
     * ??? asyncInvoke(...) ????????????????????????????????????????????? future ????????????
     */
    @Test
    public void testAsyncIo() throws Exception {
        AsyncDataStream.unorderedWait(
                kafkaDataSource,
                new AsyncIORichFunction(),
                4,
                TimeUnit.SECONDS,
                3) // 100???????????????????????????100???????????????????????????
                .print();
        streamEnv.execute("lmq-flink-demo"); //?????????
    }


    /**
     * https://developer.aliyun.com/article/706760
     *
     * @throws Exception
     */
    @Test
    public void broadcastTest() throws Exception {
        // {"ts":100,"msg":"1"} join {"ts":100,"msg":"3"} {"ts":111,"msg":"3"}  {"ts":110,"msg":"1"} {"ts":111,"msg":"1"}
        // {"ts":111,"msg":"1"} join {"ts":152,"msg":"1"}
        // {"ts":130,"msg":"4"} join {"ts":130,"msg":"3"}
        MapStateDescriptor<String, KafkaMessge> bcStateDescriptor =
                new MapStateDescriptor("d2", Types.STRING, TypeInformation.of(KafkaMessge.class));
        // d2????????????wtm??????????????????wtm?????????????????????
        BroadcastStream<KafkaMessge> bcedPatterns =
                getKafkaDataStreamSource("test", "localhost:9092", "latest")
                .broadcast(bcStateDescriptor);

        kafkaDataSource
                .connect(bcedPatterns)
                .process(new KeyedBroadcastProcessFunction<String, KafkaMessge, KafkaMessge, String>() {
                    MapStateDescriptor<String, KafkaMessge> patternDesc;
                    ValueState<KafkaMessge> tmpMsg;

                    @Override
                    public void processElement(KafkaMessge value, ReadOnlyContext ctx, Collector<String> out) throws Exception {
                        KafkaMessge d2Msg = ctx.getBroadcastState(this.patternDesc).get(value.msg());
                        if (d2Msg != null) {
                            out.collect(value + " <-> " + d2Msg);
                        } else {
                            tmpMsg.update(value);
                            // ???????????????Key???????????????????????????????????????key??????
                            ctx.timerService().registerEventTimeTimer(value.ts() + 1000L);
                        }
                    }

                    @Override
                    public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
                        System.out.println("ontime >>>>>" + tmpMsg.value());
                        KafkaMessge d2Msg = ctx.getBroadcastState(this.patternDesc).get(tmpMsg.value().msg());
                        if (d2Msg != null) {
                            out.collect(tmpMsg.value() + " <-> " + d2Msg);
                        } else
                            out.collect("onTimer: " + tmpMsg.value().toString());
                    }

                    @Override
                    public void processBroadcastElement(KafkaMessge value, Context ctx, Collector<String> out) throws Exception {
                        // store the new pattern by updating the broadcast state
                        BroadcastState<String, KafkaMessge> bcState = ctx.getBroadcastState(patternDesc);
                        // storing in MapState with null as VOID default value
                        bcState.put(value.msg(), value);

                    }

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        tmpMsg = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("tmpMsg", TypeInformation.of(KafkaMessge.class)));
                        patternDesc =
                                new MapStateDescriptor("d2", Types.STRING, TypeInformation.of(KafkaMessge.class));
                        super.open(parameters);
                    }

                })
                .returns(Types.STRING)
                .print();
        streamEnv.execute("broadcastTest");
    }

}
