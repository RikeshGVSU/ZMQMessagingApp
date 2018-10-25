# ZMQMessagingApp

**A chat application implemented using RMI and ZeroMQ.**

Run Server: Run the following command to run server - Note its a maven built. Maven needs to be installed

- mvn exec:java -Djava.rmi.server.useCodebaseOnly=false  -Djava.security.policy=policy  -Dexec.mainClass=edu.gvsu.cis.MyPresenceServer

Run Client

- mvn exec:java -Djava.rmi.server.useCodebaseOnly=false -Djava.security.policy=policy -Dexec.mainClass=edu.gvsu.cis.ChatClient -Dexec.args=bob
