all: out/artifacts/pdesai_p3_jar/Assignment\ 3.jar

out/artifacts/pdesai_p3_jar/Assignment\ 3.jar: build.xml src/distributed_banking/Branch.java src/distributed_banking/Controller.java src/distributed_banking/ProcessRequest.java src/protobuf/Bank.java libs/protobuf-java-3.4.1.jar
		ant -buildfile build.xml

clean:
		rm -rf out
