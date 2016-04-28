package com.nts.rft.client;

import com.nts.rft.sbe.MessageDecoder;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * @author Nikolay Tsankov.
 */
public class Client {

    Sender sender;
    Receiver receiver;


    public Client() {

    }

    public long balance(long account) {
        return 0;
    }

    public void transaction(long creditAccount, long debitAccount, long amount) {
        try {
            sender.sendMessage().get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    class Sender {
        Future<Object> sendMessage() {
            return null;
        }
    }

    class Receiver {
        void onMessage(MessageDecoder message) {
            if (redirect(message)) {
                changeServer();
                sender.sendMessage();
            } else {
                CompletableFuture<Object> future = null;
                future.complete(message);
            }
        }

        private void changeServer() {

        }

        boolean redirect(MessageDecoder message) {
            return false;
        }
    }


}
