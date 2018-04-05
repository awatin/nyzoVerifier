package co.nyzo.verifier;

import co.nyzo.verifier.util.PrintUtil;
import co.nyzo.verifier.util.SignatureUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Block implements MessageObject {

    public static final byte[] genesisVerifier = ByteUtil.byteArrayFromHexString("302a300506032b65-" +
            "700321006b32332d-4b28e6add7b8f86f-374045cafc645334", 32);

    public static final long genesisBlockStartTimestamp = -1L;
    public static final long blockDuration = 5000L;

    public static final File blockRootDirectory = new File(Verifier.dataRootDirectory, "blocks");
    public static final long blocksPerDirectory = 100000L;

    private long height;                           // 8 bytes; 64-bit integer block height from the Genesis block,
                                                   // which has a height of 0
    private byte[] previousBlockHash;              // 32 bytes (this is the double-SHA-256 of the previous block
                                                   // signature)
    private byte rolloverTransactionFees;          // 1 byte; remainder of transaction fees from previous block, in
                                                   // micronyzos
    private long startTimestamp;                   // 8 bytes; 64-bit Unix timestamp of the start of the block, in
                                                   // milliseconds
    private long verificationTimestamp;            // 8 bytes; 64-bit Unix timestamp of when the verifier creates the
                                                   // block, in milliseconds
    private List<Transaction> transactions;        // 4 bytes for number + variable
    private byte[] balanceListHash;                // 32 bytes (this is the double-SHA-256 of the account balance list)
    private BalanceList balanceList;               // stored separately - the hash is stored in the block
    private byte[] verifierIdentifier;             // 32 bytes
    private byte[] verifierSignature;              // 64 bytes
    private boolean fromFile = false;              // a flag to indicate whether this was read from a file; we have
                                                   // strict rules about when we write blocks to files, so these blocks
                                                   // are more thoroughly vetted than non-file blocks

    public Block(long height, byte[] previousBlockHash, byte rolloverTransactionFees, long startTimestamp,
                 List<Transaction> transactions, byte[] balanceListHash, BalanceList balanceList) {

        this.height = height;
        this.previousBlockHash = previousBlockHash;
        this.rolloverTransactionFees = rolloverTransactionFees;
        this.startTimestamp = startTimestamp;
        this.transactions = new ArrayList<>(transactions);
        this.balanceListHash = balanceListHash;
        this.balanceList = balanceList;

        try {
            this.verifierIdentifier = Verifier.getIdentifier();
            this.verifierSignature = Verifier.sign(getBytes(false));
        } catch (Exception e) {
            this.verifierIdentifier = new byte[32];
            this.verifierSignature = new byte[64];
        }
    }

    private Block(long height, byte[] previousBlockHash, byte rolloverTransactionFees, long startTimestamp,
                  List<Transaction> transactions, byte[] balanceListHash, byte[] verifierIdentifier,
                  byte[] verifierSignature) {

        this.height = height;
        this.previousBlockHash = previousBlockHash;
        this.rolloverTransactionFees = rolloverTransactionFees;
        this.startTimestamp = startTimestamp;
        this.transactions = transactions;
        this.balanceListHash = balanceListHash;
        this.verifierIdentifier = verifierIdentifier;
        this.verifierSignature = verifierSignature;
    }

    public long getBlockHeight() {
        return height;
    }

    public byte[] getPreviousBlockHash() {
        return previousBlockHash;
    }

    public byte getRolloverTransactionFees() {
        return rolloverTransactionFees;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public byte[] getHash() {
        return HashUtil.doubleSHA256(verifierSignature);
    }

    public byte[] getBalanceListHash() {
        return balanceListHash;
    }

    public BalanceList getBalanceList() {

        // The balance list should only be null for blocks made from files.
        if (balanceList == null && fromFile) {
           balanceList = BalanceList.fromFile(height);
        }
        return balanceList;
    }

    public byte[] getVerifierIdentifier() {
        return verifierIdentifier;
    }

    public byte[] getVerifierSignature() {
        return verifierSignature;
    }

    public long getTransactionFees() {

        long fees = 0L;
        for (Transaction transaction : transactions) {
            fees += transaction.getFee();
        }

        return fees;
    }

    public int getByteSize() {

        return getByteSize(true);
    }

    public int getByteSize(boolean includeSignature) {

        int size = FieldByteSize.blockHeight +           // height
                FieldByteSize.hash +                     // previous-block hash
                FieldByteSize.rolloverTransactionFees +  // rollover transaction fees
                FieldByteSize.timestamp +                // start timestamp
                4 +                                      // number of transactions
                FieldByteSize.hash;                      // digest hash
        for (Transaction transaction : transactions) {
            size += transaction.getByteSize();
        }
        if (includeSignature) {
            size += FieldByteSize.identifier + FieldByteSize.signature;
        }

        return size;
    }

    public byte[] getBytes() {

        return getBytes(true);
    }

    private byte[] getBytes(boolean includeSignature) {

        int size = getByteSize();
        System.out.println("size of block is " + size + " includeSignature=" + includeSignature);

        // Assemble the buffer.
        byte[] array = new byte[size];
        ByteBuffer buffer = ByteBuffer.wrap(array);
        buffer.putLong(height);
        buffer.put(previousBlockHash);
        buffer.put(rolloverTransactionFees);
        buffer.putLong(startTimestamp);
        buffer.putInt(transactions.size());
        for (Transaction transaction : transactions) {
            System.out.println("putting transaction: " + ByteUtil.arrayAsStringWithDashes(transaction.getBytes()));
            buffer.put(transaction.getBytes());
        }
        buffer.put(balanceListHash);
        if (includeSignature) {
            System.out.println("putting identifier: " + ByteUtil.arrayAsStringWithDashes(verifierIdentifier));
            buffer.put(verifierIdentifier);
            System.out.println("putting signature: " + ByteUtil.arrayAsStringWithDashes(verifierSignature));
            buffer.put(verifierSignature);
        }

        return array;
    }

    public void sign(byte[] signerSeed) {

        this.verifierIdentifier = KeyUtil.identifierForSeed(signerSeed);
        System.out.println("verifier identifier " + ByteUtil.arrayAsStringWithDashes(this.verifierIdentifier));
        this.verifierSignature = SignatureUtil.signBytes(getBytes(false), signerSeed);
    }

    public boolean signatureIsValid() {
        return SignatureUtil.signatureIsValid(verifierSignature, getBytes(false), verifierIdentifier);
    }

    public void writeToFile() {

        try {
            // Write the balance list to file first. Then we can assume a balance list will be available for each block
            // in a file.
            if (getBalanceList().writeToFile()) {

                File file = fileForBlockHeight(height);
                file.getParentFile().mkdirs();
                file.delete();
                Files.write(Paths.get(file.getAbsolutePath()), getBytes());

                BlockManager.setHighestBlockFrozen(height);
            } else {
                System.err.println("unsuccessful writing balance list for block " + height);
            }
        } catch (Exception reportOnly) {
            reportOnly.printStackTrace();
            System.err.println("exception writing block to file " + reportOnly.getMessage());
        }
    }

    public static Block fromFile(long height) {

        File file = fileForBlockHeight(height);
        Block block = null;
        Exception e = new Exception();
        StackTraceElement[] stackTrace = e.getStackTrace();
        System.out.println("looking for file " + file.getAbsolutePath() + ": " + stackTrace[1]);
        if (file.exists()) {
            try {
                byte[] fileBytes = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
                System.out.println("byte array length: " + fileBytes.length);
                block = fromBytes(fileBytes);
                block.fromFile = true;
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
        }

        return block;
    }

    public static Block fromBytes(byte[] bytes) {

        return fromByteBuffer(ByteBuffer.wrap(bytes));
    }

    public static Block fromByteBuffer(ByteBuffer buffer) {

        long blockHeight = buffer.getLong();
        byte[] previousBlockHash = new byte[FieldByteSize.hash];
        buffer.get(previousBlockHash);
        byte rolloverTransactionFees = buffer.get();
        long startTimestamp = buffer.getLong();

        int numberOfTransactions = buffer.getInt();
        System.out.println("need to read " + numberOfTransactions + " transactions");
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < numberOfTransactions; i++) {
            transactions.add(Transaction.fromByteBuffer(buffer));
        }

        byte[] balanceListHash = new byte[FieldByteSize.hash];
        buffer.get(balanceListHash);
        byte[] verifierIdentifier = new byte[FieldByteSize.identifier];
        buffer.get(verifierIdentifier);
        byte[] verifierSignature = new byte[FieldByteSize.signature];
        buffer.get(verifierSignature);

        return new Block(blockHeight, previousBlockHash, rolloverTransactionFees, startTimestamp, transactions,
                balanceListHash, verifierIdentifier, verifierSignature);
    }

    public static File directoryForBlockHeight(long height) {

        long directoryIndex = height / blocksPerDirectory;
        return new File(blockRootDirectory, String.format("%05d", directoryIndex));
    }

    public static File fileForBlockHeight(long height) {

        File directory = directoryForBlockHeight(height);
        return new File(directory, String.format("%010d.nyzoblock", height));
    }

    public static BalanceList balanceListForNextBlock(Block previousBlock, List<Transaction> transactions,
                                                      byte[] verifierIdentifier) {

        BalanceList result = null;
        try {
            // For the Genesis block, start with an empty balance list and no rollover fees. For all others, start with
            // the information from the previous block's balance list.
            long blockHeight;
            List<BalanceListItem> previousBalanceItems;
            long previousRolloverFees;
            if (previousBlock == null) {
                blockHeight = 0L;
                previousBalanceItems = new ArrayList<>();
                previousRolloverFees = 0L;
            } else {
                blockHeight = previousBlock.getBlockHeight() + 1L;
                BalanceList previousBalanceList = previousBlock.getBalanceList();
                previousBalanceItems = previousBalanceList.getItems();
                previousRolloverFees = previousBalanceList.getRolloverFees();
            }

            // Make a map of the identifiers to balances.
            Map<ByteBuffer, Long> identifierToBalanceMap = new HashMap<>();
            for (BalanceListItem item : previousBalanceItems) {
                identifierToBalanceMap.put(ByteBuffer.wrap(item.getIdentifier()), item.getBalance());
            }

            // Add all transactions.
            long feesThisBlock = 0L;
            for (Transaction transaction : transactions) {
                System.out.println("processing transaction of amount " + transaction.getAmount());
                feesThisBlock += transaction.getFee();
                if (transaction.getType() != Transaction.typeCoinGeneration) {
                    adjustBalance(transaction.getSenderIdentifier(), -transaction.getAmount(), identifierToBalanceMap);
                }
                long amountAfterFee = transaction.getAmount() - transaction.getFee();
                if (amountAfterFee > 0) {
                    adjustBalance(transaction.getReceiverIdentifier(), amountAfterFee, identifierToBalanceMap);
                }
            }

            // Split the transaction fees among as many as the previous 10 verifiers. For blocks of height under 9,
            // there will be fewer verifiers to share, so each gets a larger portion of fees (block 0 = 100%,
            // block 1 = ½, block 2 = ⅓,... block 8 = 1/9, block 9 and above = 10%). Fee divisions are always rounded
            // down, and the remainder is stored to be added to the fees for the next block.
            List<byte[]> verifierIdentifiers = new ArrayList<>();
            for (long i = blockHeight - 1; i >= 0 && i >= blockHeight - 9; i--) {
                //verifierIdentifiers.add(verifierForBlock(i));
            }
            // TODO: add previous verifier identifiers
            // TODO: we need to deal with both frozen and unfrozen (chain option) blocks properly
            verifierIdentifiers.add(verifierIdentifier);
            long totalFees = feesThisBlock + previousRolloverFees;
            long feesPerVerifier = totalFees / verifierIdentifiers.size();
            if (feesPerVerifier > 0L) {
                for (byte[] identifier : verifierIdentifiers) {
                    adjustBalance(identifier, feesPerVerifier, identifierToBalanceMap);
                }
            }

            // Make the new balance items from the balance map.
            List<BalanceListItem> balanceItems = new ArrayList<>();
            for (ByteBuffer identifier : identifierToBalanceMap.keySet()) {
                long balance = identifierToBalanceMap.get(identifier);
                if (balance > 0L) {
                    balanceItems.add(new BalanceListItem(identifier.array(), balance));
                }
            }

            // Make the digest. The pairs are sorted in the constructor.
            byte rolloverFees = (byte) (totalFees % verifierIdentifiers.size());
            result = new BalanceList(blockHeight, rolloverFees, balanceItems);

        } catch (Exception ignored) { ignored.printStackTrace(); }

        return result;
    }

    private static void adjustBalance(byte[] identifier, long amount, Map<ByteBuffer, Long> identifierToBalanceMap) {

        System.out.println("adjusting balance for identifier " + ByteUtil.arrayAsStringWithDashes(identifier) + " by " +
                amount);
        ByteBuffer identifierBuffer = ByteBuffer.wrap(identifier);
        Long balance = identifierToBalanceMap.get(identifierBuffer);
        if (balance == null) {
            balance = 0L;
        }
        balance += amount;
        identifierToBalanceMap.put(identifierBuffer, balance);
    }

    public static boolean isValidGenesisBlock(Block block, StringBuilder error) {

        boolean valid = true;
        if (block.getBlockHeight() != 0L) {
            error.append("The block height is " + block.getBlockHeight() + ", but the only valid height for a " +
                    "Genesis block is 0. ");
            valid = false;
        }

        if (block.getTransactions().size() == 1) {
            Transaction transaction = block.getTransactions().get(0);
            if (transaction.getType() != Transaction.typeCoinGeneration) {
                error.append("The only valid transaction type in the Genesis block is coin generation (type " +
                        Transaction.typeCoinGeneration + "). The transaction in this block is of type " +
                        transaction.getType() + ". ");
                valid = false;
            }
            if (transaction.getAmount() != Transaction.micronyzosInSystem) {
                error.append("The Genesis block transaction must be for exactly " +
                        PrintUtil.printAmount(Transaction.micronyzosInSystem) + ". The transaction in this block is " +
                        "for " + PrintUtil.printAmount(transaction.getAmount()) + ". ");
                valid = false;
            }
        } else {
            error.append("The Genesis block must have exactly 1 transaction. This block has " +
                    block.getTransactions().size() + " transactions. ");
            valid = false;
        }

        if (!ByteUtil.arraysAreEqual(block.getVerifierIdentifier(), Block.genesisVerifier)) {
            error.append("The Genesis block must be verified by " +
                    ByteUtil.arrayAsStringWithDashes(Block.genesisVerifier) + ". This block was verified by " +
                    ByteUtil.arrayAsStringWithDashes(block.getVerifierIdentifier()) + ". ");
            valid = false;
        }

        if (!block.signatureIsValid()) {
            error.append("The signature is not valid. ");
            valid = false;
        }

        if (error.charAt(error.length() - 1) == ' ') {
            error.deleteCharAt(error.length() - 1);
        }

        return valid;
    }
}
