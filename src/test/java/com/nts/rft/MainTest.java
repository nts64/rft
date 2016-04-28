package com.nts.rft;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Created by Nikolay Tsankov
 */
public class MainTest {
    private static final Logger logger = LoggerFactory.getLogger(MainTest.class);

    @Test
    @Ignore
    public void testMain() {
        logger.info("TestMain");
        MediaDriver driver = MediaDriver.launchEmbedded();
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(driver.aeronDirectoryName());
        Aeron aeron = Aeron.connect(context);

        Subscription subscription = aeron.addSubscription("udp://localhost:50000", 11);
        IdleStrategy idleStrategy = new SleepingIdleStrategy(TimeUnit.MILLISECONDS.toNanos(100));
        //startPublisher(aeron);
        Map<String, Publication> clients = new HashMap<>();
        UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

        while (true) {
            idleStrategy.idle(subscription.poll((buffer, offset, length, header) -> {
                byte[] data = new byte[length];
                buffer.getBytes(offset, data);
                String msg = new String(data);
                System.out.println(String.format(
                        "Message to stream %d from session %d (%d@%d) <<%s>>",
                        11, header.sessionId(), length, offset, msg));
                Publication p = clients.computeIfAbsent(msg, s -> {
                    String[] split = msg.split(";");
                    return aeron.addPublication(split[0], Integer.parseInt(split[1]));
                });
                String message = "reply to " + msg;
                data = message.getBytes();
                buf.putBytes(0, data);
                p.offer(buf, 0, data.length);
            }, 10));
        }
    }

    private Thread startPublisher(Aeron aeron) {
        int id = 2;
        Subscription subscription = aeron.addSubscription("udp://localhost:52001", id);
        Thread sub = new Thread(() -> {
            IdleStrategy idleStrategy = new SleepingIdleStrategy(TimeUnit.MILLISECONDS.toNanos(10));
            while (true) {
                idleStrategy.idle(subscription.poll((buffer, offset, length, header) -> {
                    byte[] data = new byte[length];
                    buffer.getBytes(offset, data);
                    System.out.println(String.format(
                            "Message to stream %d from session %d (%d@%d) <<%s>>",
                            22, header.sessionId(), length, offset, new String(data)));
                }, 10));
            }
        });
        sub.start();

        Runnable runnable = () -> {
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
            Publication publication = aeron.addPublication("udp://localhost:50000", 11);
            while (true) {
                String message = "udp://localhost:52001;" + id;
                byte[] data = message.getBytes();
                buffer.putBytes(0, data);
                long result = publication.offer(buffer, 0, data.length);
                if (result < 0L) {
                    if (result == Publication.BACK_PRESSURED) {
                        System.out.println("Offer failed due to back pressure");
                    } else if (result == Publication.NOT_CONNECTED) {
                        System.out.println("Offer failed because publisher is not yet connected to subscriber");
                    } else if (result == Publication.ADMIN_ACTION) {
                        System.out.println("Offer failed because of an administration action in the system");
                    } else if (result == Publication.CLOSED) {
                        System.out.println("Offer failed publication is closed");
                        break;
                    } else {
                        System.out.println("Offer failed due to unknown reason");
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
        return thread;
    }

    @Test
    @Ignore
    public void testPublisher() throws InterruptedException {
        logger.info("testPublisher");
        MediaDriver driver = MediaDriver.launchEmbedded();
        Aeron.Context context = new Aeron.Context();
        context.aeronDirectoryName(driver.aeronDirectoryName());
        Aeron aeron = Aeron.connect(context);

        startPublisher(aeron).join();
    }
}
