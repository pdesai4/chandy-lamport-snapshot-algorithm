package distributed_banking;

import protobuf.Bank;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Controller {

    private static Map<Integer, String[]> snapshots = new Hashtable<>();

    public static void main(String[] args) throws IOException {
        int totalMoney = Integer.parseInt(args[0]);
        List<Socket> socketList = new ArrayList<>();
        String fileIn = args[1];
        BufferedReader bufferedReader = new BufferedReader(new FileReader(fileIn));
        String currentLine;
        int numberOfBranches = 0;
        List<String> branchIp = new ArrayList<>();
        List<String> branchPort = new ArrayList<>();
        List<Bank.InitBranch.Branch> branchList = new ArrayList<>();
        while ((currentLine = bufferedReader.readLine()) != null) {
            numberOfBranches++;
            String[] strings = currentLine.split(" ");
            branchIp.add(strings[1]);
            branchPort.add(strings[2]);

            Bank.InitBranch.Branch branch = Bank.InitBranch.Branch.newBuilder()
                    .setName(strings[0])
                    .setIp(strings[1])
                    .setPort(Integer.parseInt(strings[2]))
                    .build();
            branchList.add(branch);
        }
        bufferedReader.close();

        // Send init branch message to all branches
        Bank.InitBranch initBranch = Bank.InitBranch.newBuilder()
                .setBalance(totalMoney / numberOfBranches)
                .addAllAllBranches(branchList)
                .build();
        Bank.BranchMessage branchMessage = Bank.BranchMessage.newBuilder().setInitBranch(initBranch).build();
        for (int i = 0; i < branchIp.size(); i++) {
            Socket socket = new Socket(branchIp.get(i), Integer.parseInt(branchPort.get(i)));
            socketList.add(socket);
            branchMessage.writeDelimitedTo(socket.getOutputStream());
        }

        // Wait till all branches are connected to each other
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int snapshotId = 1;

        while (!Thread.interrupted()) {

            // Send snapshotRequest
            requestSnapshot(socketList, snapshotId);

            // Wait till all snapshots are ready
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Retrieve snapshot
            retrieveSnapshot(socketList, snapshotId, branchList);

            // Wait till all snapshots are received
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Print output
            System.out.println("snapshot_id: " + snapshotId);
            for (String op : snapshots.get(snapshotId)) {
                System.out.println(op);
            }
            snapshotId++;
        }

        for (Socket socket : socketList) {
            socket.close();
        }
    }

    private static void retrieveSnapshot(List<Socket> socketList, int snapshotId, List<Bank.InitBranch.Branch> branchList) {
        for (int i = 0; i < socketList.size(); i++) {
            Socket socket = socketList.get(i);
            Bank.RetrieveSnapshot retrieveSnapshot = Bank.RetrieveSnapshot.newBuilder()
                    .setSnapshotId(snapshotId)
                    .build();
            Bank.BranchMessage branchMessage = Bank.BranchMessage.newBuilder().setRetrieveSnapshot(retrieveSnapshot).build();
            try {
                branchMessage.writeDelimitedTo(socket.getOutputStream());
                int finalI = i;
                new Thread(() -> {
                    Bank.BranchMessage branchMessageRt;
                    try {
                        //noinspection StatementWithEmptyBody
                        while ((branchMessageRt = Bank.BranchMessage.parseDelimitedFrom(socket.getInputStream())) == null)
                            ;
                        Bank.ReturnSnapshot.LocalSnapshot localSnapshot = branchMessageRt.getReturnSnapshot().getLocalSnapshot();

                        // Print output
                        StringBuilder stringBuilder = new StringBuilder();
                        stringBuilder.append(branchList.get(finalI).getName()).append(": ").append(localSnapshot.getBalance());
                        List<Integer> channelStateList = localSnapshot.getChannelStateList();
                        for (int i1 = 0; i1 < channelStateList.size(); i1 += 2) {
                            stringBuilder.append(", ");
                            stringBuilder.append(branchList.get(i1).getName())
                                    .append("->")
                                    .append(branchList.get(finalI).getName())
                                    .append(": ")
                                    .append(channelStateList.get(i1 + 1));
                        }
                        snapshots.get(snapshotId)[finalI] = stringBuilder.toString();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void requestSnapshot(List<Socket> socketList, int snapshotId) {
        Random rand = new Random();
        int index = rand.nextInt(socketList.size());
        Bank.InitSnapshot initSnapshot = Bank.InitSnapshot.newBuilder()
                .setSnapshotId(snapshotId)
                .build();
        Bank.BranchMessage branchMessage = Bank.BranchMessage.newBuilder().setInitSnapshot(initSnapshot).build();
        try {
            branchMessage.writeDelimitedTo(socketList.get(index).getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        snapshots.put(snapshotId, new String[socketList.size()]);
    }

}
