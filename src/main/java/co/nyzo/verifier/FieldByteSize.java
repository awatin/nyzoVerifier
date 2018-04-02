package co.nyzo.verifier;

import java.nio.charset.StandardCharsets;

public class FieldByteSize {

    public static final int booleanField = 1;
    public static final int balanceListLength = 4;
    public static final int ipAddress = 4;
    public static final int port = 4;
    public static final int rolloverTransactionFees = 1;
    public static final int transactionType = 1;
    public static final int transactionAmount = 8;
    public static final int timestamp = 8;
    public static final int blockHeight = 8;
    public static final int messageType = 2;
    public static final int hash = 32;
    public static final int identifier = 32;
    public static final int nodeListLength = 4;
    public static final int senderData = 32;
    public static final int signature = 64;
    public static final int stringLength = 2;

    public static int string(String value) {
        return stringLength + (value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
    }
}
