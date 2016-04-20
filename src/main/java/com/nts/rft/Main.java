package com.nts.rft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by Nikolay Tsankov
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            logger.info("Starting with arguments: {}", Arrays.toString(args));
            Server server = new Server(new Settings(Integer.parseInt(args[0])));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        } catch (Exception e) {
            logger.error("Error caught", e);
        }
    }
}
