**Chandy-Lamport Snapshot Algorithm**

Implement a distributed banking application with multiple bank branches.

**Compiling and Execution:**

{Just run the scripts. The scripts will compile and run the project.}
	`$ ./branch <branch_name> <port_number>`
	This will run the branch at the given port with the given name

After running the branches, create a text file with the server ip and port written in the format "<branch_name> <ip> <port>".
Then,
	`$ ./controller <balance> <input_file_name>`
	This will run the controller

**Implementation:**

Branch.java -
	This class basically represents the branch and performs all the operations of a branch.

ProcessRequest.java -
	This class process each thread.

Controller.java -
	This class is the controller, which initializes all the branches, sends the snapshot request to one of the branch and waits
	for the snapshot from all the branches.