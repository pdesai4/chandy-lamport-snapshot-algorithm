package distributed_banking;

import protobuf.Bank;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Branch {
    private final Object balanceLock;
    private Map<Integer, Bank.ReturnSnapshot.LocalSnapshot> snapshot;
    private List<String> initBranches;
    private Map<Socket, String> connections;
    private List<Socket> keySockets;
    private int branchBalance;
    private int totalNumberOfBranches;
    private Map<Integer, boolean[]> isRecording;
    private String branchName;
    private Random rand;

    private Branch() {
        balanceLock = new Object();
        connections = new Hashtable<>();
        snapshot = new HashMap<>();
        keySockets = new ArrayList<>();
        initBranches = new ArrayList<>();
        isRecording = new HashMap<>();
        branchBalance = 0;
        totalNumberOfBranches = 0;
        rand = new Random(System.currentTimeMillis());
    }

    public static void main(String[] args) {
        Branch branch = new Branch();
        branch.branchName = args[0];
        branch.start(args);
    }

    String getBranchName() {
        return branchName;
    }

    private void start(String[] args) {
        int portNumber = Integer.parseInt(args[1]);

        // Start listening on given port number
        ServerSocket serverSocket = null;
        String hostName = null;
        try {
            serverSocket = new ServerSocket(portNumber);
            hostName = InetAddress.getLocalHost().getCanonicalHostName();
            System.out.println(branchName + " running on host " + hostName + " and port number " + portNumber);
        } catch (IOException e) {
            System.err.println("Error: Unable to establish server");
            System.exit(1);
        }

        Socket socket;
        try {
            // For controller request
            socket = serverSocket.accept();
            Thread requestThread = new ProcessRequest(socket, hostName, portNumber, this, false);
            requestThread.start();
            // Other branches
            while (!Thread.interrupted()) {
                try {
                    socket = serverSocket.accept();
                    Thread newThread = new ProcessRequest(socket, hostName, portNumber, this, true);
                    newThread.start();
                } catch (IOException e) {
                    System.err.println("Error: Server unable to accept client request");
                }
            }
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void newSocketCreated(String branchName, Socket socket) {
        connections.put(socket, branchName);
        keySockets.add(socket);
        if (connections.size() == totalNumberOfBranches - 1) {
            Runnable transferMoney = this::sendMoney;
            new Thread(transferMoney).start();
        }
    }

    void setTotalNumberOfBranches(int totalNumberOfBranches) {
        this.totalNumberOfBranches = totalNumberOfBranches;
    }

    private void sendMoney() {
        while (!Thread.interrupted()) {
            rand = new Random();
            int interval = rand.nextInt(5) * 1000;
            //int interval = 3;
            try {
                Thread.sleep(interval);
                sendTransferMessage();
            } catch (InterruptedException e) {
                System.out.println("Error: Interruption in thread delay");
                e.printStackTrace();
            }
        }
    }

    private void sendTransferMessage() {
        synchronized (balanceLock) {
            if (branchBalance != 0) {
                Random rand = new Random();
                int randomIndex = rand.nextInt(keySockets.size());
                Socket keySocket = keySockets.get(randomIndex);
                // Calculate the amount to be sent
                int amountPercent = rand.nextInt(5) + 1;
                int sendAmount = ((branchBalance * amountPercent) / 100);
                branchBalance = branchBalance - sendAmount;
                // Form the transfer message
                Bank.Transfer branch = Bank.Transfer.newBuilder()
                        .setMoney(sendAmount)
                        .build();
                Bank.BranchMessage branchMessage = Bank.BranchMessage.newBuilder().setTransfer(branch).build();
                // Send message to the output stream
                try {
                    branchMessage.writeDelimitedTo(keySocket.getOutputStream());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    void setBranchBalance(int branchBalance) {
        synchronized (balanceLock) {
            this.branchBalance = branchBalance;
        }
    }

    void addBranchBalance(int amount, Socket socket) {
        synchronized (balanceLock) {
            this.branchBalance += amount;
            recordChannels(amount, socket);
        }
    }

    Map<Socket, String> getConnectionsMap() {
        return connections;
    }

    void setInitBranches(int i, String branchName) {
        initBranches.add(i, branchName);
    }

    void initSnapshotRequest(int snapshotId) {
        // When branch state is recorded, all other messages should be sent after the marker messages only
        // on respective channel
        synchronized (balanceLock) {
            Bank.ReturnSnapshot.LocalSnapshot localSnapshot = Bank.ReturnSnapshot.LocalSnapshot.newBuilder()
                    .setBalance(branchBalance)
                    .setSnapshotId(snapshotId)
                    .addAllChannelState(new ArrayList<>())
                    .build();
            snapshot.put(snapshotId, localSnapshot);
            sendMarkerMessage(snapshotId);
            // Start recording for all channels
            for (int i = 0; i < initBranches.size(); i++) {
                startRecording(snapshotId, i);
            }
        }
    }

    void receivedMarker(int snapshotId, Socket receivedFrom) {
        synchronized (balanceLock) {
            // First Marker
            if (!snapshot.keySet().contains(snapshotId)) {
                // Start recording for all channels
                for (int i = 0; i < initBranches.size(); i++) {
                    startRecording(snapshotId, i);
                }

                // Stop recording for incoming channel received this marker from
                String branchNameReceivedFrom = connections.get(receivedFrom);
                stopRecording(snapshotId, initBranches.indexOf(branchNameReceivedFrom));

                // Record balance
                Bank.ReturnSnapshot.LocalSnapshot localSnapshot = Bank.ReturnSnapshot.LocalSnapshot.newBuilder()
                        .setBalance(branchBalance)
                        .addAllChannelState(new ArrayList<>())
                        .setSnapshotId(snapshotId)
                        .build();
                snapshot.put(snapshotId, localSnapshot);

                // Send Marker message
                sendMarkerMessage(snapshotId);

            } else {
                // Not first Marker
                // Stop recording for all channels
                String branchNameReceivedFrom = connections.get(receivedFrom);
                stopRecording(snapshotId, initBranches.indexOf(branchNameReceivedFrom));
            }
        }
    }

    private void startRecording(int snapshotId, int index) {
        boolean[] booleans = isRecording.get(snapshotId);
        if (booleans == null) {
            isRecording.put(snapshotId, new boolean[initBranches.size()]);
        }
        isRecording.get(snapshotId)[index] = true;
    }

    private void stopRecording(int snapshotId, int index) {
        synchronized (balanceLock) {
            isRecording.get(snapshotId)[index] = false;
        }
    }

    private void recordChannels(int moneyTransferred, Socket receivingSocket) {
        String branchName = connections.get(receivingSocket);
        int index = initBranches.indexOf(branchName);
        for (int snapshotId : isRecording.keySet()) {
            if (isRecording.get(snapshotId)[index]) {
                Bank.ReturnSnapshot.LocalSnapshot localSnapshot = snapshot.get(snapshotId);
                localSnapshot.getChannelStateList().add(index);
                localSnapshot.getChannelStateList().add(moneyTransferred);
            }
        }
    }

    void retrieveSnapshot(int snapshotId, Socket receivedFrom) {
        Bank.ReturnSnapshot.LocalSnapshot localSnapshot = snapshot.get(snapshotId);
        returnSnapshot(receivedFrom, localSnapshot);
    }

    private void returnSnapshot(Socket returnTo, Bank.ReturnSnapshot.LocalSnapshot localSnapshot) {
        Bank.ReturnSnapshot returnSnapshot = Bank.ReturnSnapshot.newBuilder()
                .setLocalSnapshot(localSnapshot)
                .build();
        Bank.BranchMessage branchMessage = Bank.BranchMessage.newBuilder().setReturnSnapshot(returnSnapshot).build();
        try {
            branchMessage.writeDelimitedTo(returnTo.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendMarkerMessage(int snapshotId) {
        // Form marker message
        Bank.Marker marker = Bank.Marker.newBuilder()
                .setSnapshotId(snapshotId)
                .build();
        Bank.BranchMessage branchMessage = Bank.BranchMessage.newBuilder().setMarker(marker).build();

        // Send marker message
        for (Socket socket : getConnectionsMap().keySet()) {
            try {
                branchMessage.writeDelimitedTo(socket.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}