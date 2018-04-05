package distributed_banking;

import protobuf.Bank;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

public class ProcessRequest extends Thread {
    private Socket socket;
    private String hostName;
    private int portNumber;
    private Branch branch;
    private boolean waitForName;

    ProcessRequest(Socket socket, String hostName, int portNumber, Branch branch, boolean waitForName) {
        this.socket = socket;
        this.hostName = hostName;
        this.portNumber = portNumber;
        this.branch = branch;
        this.waitForName = waitForName;
    }

    public void run() {
        try {
            if (waitForName) {
                String branchName;
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                // noinspection StatementWithEmptyBody
                while ((branchName = bufferedReader.readLine()) == null) ;
                branch.newSocketCreated(branchName, socket);
            }
            Bank.BranchMessage branchMessage;
            while (!interrupted() && (branchMessage = Bank.BranchMessage.parseDelimitedFrom(socket.getInputStream())) != null) {
                switch (branchMessage.getBranchMessageCase()) {
                    case INIT_BRANCH:
                        Bank.InitBranch initBranch = branchMessage.getInitBranch();
                        initMessage(initBranch);
                        break;
                    case TRANSFER:
                        transferMessage(branchMessage.getTransfer().getMoney(), socket);
                        break;
                    case INIT_SNAPSHOT:
                        initSnapshot(branchMessage.getInitSnapshot().getSnapshotId());
                        break;
                    case MARKER:
                        markerMessage(branchMessage.getMarker().getSnapshotId(), socket);
                        break;
                    case RETRIEVE_SNAPSHOT:
                        retrieveSnapshot(branchMessage.getRetrieveSnapshot().getSnapshotId(), socket);
                        break;
                    case RETURN_SNAPSHOT:
                        break;
                    case BRANCHMESSAGE_NOT_SET:
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void retrieveSnapshot(int snapshotId, Socket receivedFrom) {
        branch.retrieveSnapshot(snapshotId, receivedFrom);
    }

    private void markerMessage(int snapshotId, Socket receivedFrom) {
        branch.receivedMarker(snapshotId, receivedFrom);
    }

    private void initSnapshot(int snapshotId) {
        branch.initSnapshotRequest(snapshotId);
    }

    private void initMessage(Bank.InitBranch initBranch) {
        int selfIndex = 0;
        branch.setTotalNumberOfBranches(initBranch.getAllBranchesCount());
        branch.setBranchBalance(initBranch.getBalance());

        // Tell branch about the names of all the branches
        for (int i = 0; i < initBranch.getAllBranchesCount(); i++) {
            branch.setInitBranches(i, initBranch.getAllBranches(i).getName());
        }

        // Identify which branch is this
        for (int i = 0; i < initBranch.getAllBranchesCount(); i++) {
            if (initBranch.getAllBranches(i).getName().equals(branch.getBranchName())) {
                selfIndex = i;
                break;
            }
        }

        // Wait till every branch receives Init from controller
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Connect to all branch below the current branch
        for (int i = selfIndex + 1; i < initBranch.getAllBranchesCount(); i++) {
            try {
                Bank.InitBranch.Branch branchToConnectTo = initBranch.getAllBranches(i);
                Socket socket = new Socket(branchToConnectTo.getIp(), branchToConnectTo.getPort());
                String message = initBranch.getAllBranches(selfIndex).getName() + "\n";
                BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                bufferedWriter.write(message);
                bufferedWriter.flush();
                ProcessRequest requestThread = new ProcessRequest(socket, hostName, portNumber, branch, false);
                requestThread.start();
                branch.newSocketCreated(branchToConnectTo.getName(), socket);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void transferMessage(int moneyTransferred, Socket socket) {
        branch.addBranchBalance(moneyTransferred, socket);
    }
}
