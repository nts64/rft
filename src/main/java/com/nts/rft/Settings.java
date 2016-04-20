package com.nts.rft;

import org.agrona.TimerWheel;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * @author Nikolay Tsankov.
 */
class Settings {
    static final boolean LOG_TIME_ENABLED = true;
    static final boolean LOG_TRACE_ENABLED = true;
    static final boolean LOG_WARN_ENABLED = true;

    final long heartbeatTimeout = 2000;
    final long electionTimeout = 15000 + new Random().nextInt(15000);
    final int clusterSize = 5;
    final String channelPrefix = "udp://localhost:400";
    final IdleStrategy idleStrategy = new SleepingIdleStrategy(TimeUnit.MILLISECONDS.toNanos(1));
    final TimerWheel timerWheel = new TimerWheel(500, TimeUnit.MILLISECONDS, 512);

    final int id;

    Settings(int id) {
        this.id = id;
    }
}
