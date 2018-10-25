package edu.gvsu.cis;

/**
 * <p>Title: Lab2</p>
 * <p>Description: Old School Instant Messaging Application </p>
 * @author Jonathan Engelsma
 * @version 1.0
 */

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.Vector;
import java.net.*;
import java.io.*;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;

/**
 * A simple chat client.
 */
public class ChatClient 
{

	PresenceService nameServer;
    ZMQ.Socket socket = null;
    SvrThread svrThread;
    RegistrationInfo regInfo;
    int port;
    String myHost;

    /**
     * Constructor.
     * @param uname The name of the chimp running the client.
     * @parm hostPortStr The host/port string in the form host:port
     * where the ":port" portion is optional. This is the host/port
     * of the presence service we are connecting to.  If set to null,
     * we'll attempt to connect to port 1099 on the localhost.
     */
    public ChatClient(String uname,String hostPortStr)
    {
        // Step 0. Figure out local host name.
        try {
            myHost = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            myHost = "localhost";
        }

        // Step 1. We need to establish a server socket where we will listen for
        // incoming chat requests.
        try {
        	ZMQ.Context context = ZMQ.context(1);
            socket = context.socket(ZMQ.REP);
        } catch (Exception e) {
            System.out.println("Error: Couldn't allocate local socket endpoint.");
            System.exit(-1);
        }
        
        

        // Step 2. we bind to the nameserver so we can register our client.
        if(hostPortStr == null) {
            hostPortStr = myHost;
        }
        System.out.println("User name is " + uname);

        System.out.println("Trying to connect to name server at " + hostPortStr);
        try {
            this.nameServer = (PresenceService)Naming.lookup("//" + hostPortStr + "/PresenceService");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Unable to bind to name server.  Make sure its running.");
            System.exit(1);
        }

        System.out.println("Registering...");
        port = socket.bindToRandomPort("tcp://*", 6001, 6020);
        // Step 3. Create the registration info bundle.
        this.regInfo = new RegistrationInfo(uname,myHost,this.port,true);

        // Step 4. register the client with the presence service to advertise it
        // is available for chatting.
        try {
            if(!this.nameServer.register(this.regInfo)) {
                System.out.println("Sorry, that username is already taken.  Please try another.");
                System.exit(1);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
            System.out.println("Error: Failed to register with name service.");
            System.exit(1);
        }

        // Step 5. Kick off a separate thread to listen to incoming requests on the
        // Server socket.
        this.svrThread = new SvrThread();
        Thread t = new Thread(this.svrThread);
        t.start();
    }



    /**
     * Simple command shell that interprets commands from the user.
     */
    public void runCmdShell()
    {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        boolean done = false;
        String cmd;

        // Read and process commands from standard input, until done.
        while(!done) {

        	this.promptUser();

            try {

                // Read an input line.
                cmd = in.readLine();

                // If we have a valid command, try to parse/process it.
                if(cmd!=null) {

                    if(cmd.toLowerCase().trim().startsWith("chat")) {
                        // look up user in the presence server.
                        String str = cmd.toLowerCase().trim();
                        int pos = str.indexOf(' ');
                        if(pos == -1) {
                            System.out.println("Missing userName.  Enter: chat {username} {msg}");
                            continue;
                        }
                        str = str.substring(pos+1);
                        pos = str.indexOf(' ');
                        String name;
                        String msg;
                        if(pos == -1) {
                            name = str;
                            msg = "";
                        } else {
                            name = str.substring(0,pos);
                            msg = str.substring(pos+1);
                        }

                        if(!lookupAndSendMsg(name,msg)) {
                            System.out.println("'" + name + "' is not currently online or is unavailable.");
                            continue;
                        }

                    } else if(cmd.toLowerCase().trim().startsWith("friends")) {

                        Vector<RegistrationInfo> clients = this.nameServer.listRegisteredUsers();
                        if(clients != null) {
                            System.out.println("\nThe following users are logged on:\n");
                            for(RegistrationInfo client : clients) {
                                System.out.println(client.getUserName() + (client.getStatus() ? " (available)":" (away)") );
                            }
                        } else {
                            System.out.println("No users are currently registered.\n");
                        }

                    } else if(cmd.toLowerCase().trim().startsWith("broadcast")) {

                        int pos = cmd.indexOf(' ');
                        if(pos == -1) {
                            System.out.println("Missing the message.  Enter: broadcast {msg}");
                            continue;
                        }
                        String msg = cmd.substring(pos+1);
                        Vector<RegistrationInfo> clients = this.nameServer.listRegisteredUsers();
                        if(clients != null) {
                            System.out.println("\nBroadcasting to the following users:\n");
                            for(RegistrationInfo client : clients) {
                                String userName = client.getUserName();
                                // Don't broadcast to the local client!
                                if(!userName.equals(this.regInfo.getUserName())) {
                                    System.out.print("Sending message to " + userName + " ... " );
                                    if(!this.sendMsgToKnownUser(client, msg)) {
                                        System.out.println("failed (unavailable).");
                                    } else {
                                        System.out.println("Done!");
                                    }
                                }
                             }
                        } else {
                            System.out.println("No users to broadcast to.\n");
                        }
                        
                    } else if(cmd.toLowerCase().trim().startsWith("busy")) { 
                    		if(this.regInfo.getStatus()) {
	                    		this.regInfo.setStatus(false);
	                    		this.nameServer.updateRegistrationInfo(this.regInfo);
	                    		System.out.println("Status currently set to unavailable");
                    		}
                    		
                    } else if(cmd.toLowerCase().trim().startsWith("available")) { 
                		if(!this.regInfo.getStatus()) {
                    		this.regInfo.setStatus(true);
                    		this.nameServer.updateRegistrationInfo(this.regInfo);
                    		System.out.println("Status currently set to available");
                		}
                    			

                    } else if(cmd.toLowerCase().trim().startsWith("exit")) {

                        // we need to unregister from name server.
                        if(this.nameServer != null) {
                            System.out.println("Logging off.");
                            this.nameServer.unregister(this.regInfo.getUserName());
                        }
                        this.svrThread.stop();
                        System.out.println("Goodbye.");
                        done = true;
                    } else {
                        System.out.println("Hmm, not sure what you meant there. Try again.");
                    }

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Send a message to a user with the given name.
     * @param userName The name of the user you wish to send the message to.
     * @param msg The msg string you wish to send the user.
     * @return true if the message was sent, false otherwise.
     */
    private boolean lookupAndSendMsg(String userName, String msg)
    {
        boolean retval = true;
        try {
            // look up this user's registration info so we can send message.
            RegistrationInfo reg = this.nameServer.lookup(userName);
            retval = this.sendMsgToKnownUser(reg, msg);
        } catch (RemoteException re) {
            System.out.println("Could not connect to presence server!");
            retval = false;
        }
        return retval;
    }
    
    /**
     * Send a message to a user with the given RegistrationInfo.
     * @param reg The RegistrationInfo of the user you wish to send the message to.
     * @param msg The msg string you wish to send the user.
     * @return true if the message was sent, false otherwise.
     */
    private boolean sendMsgToKnownUser(RegistrationInfo reg, String msg)
    {
    	boolean retval = true;
        if(reg==null || !reg.getStatus()) {
            // User is not registered!
            retval = false;
        } else {
            try {
            	ZMQ.Context context = ZMQ.context(1);
            	ZMQ.Socket socket = context.socket(ZMQ.REQ);
            	socket.connect("tcp://"+reg.getHost() +":"+ reg.getPort());
            	String completeMsg = "Message from " + this.regInfo.getUserName() + ": " + msg + "\n"; 
            	socket.send(completeMsg.getBytes(), 0);
            	socket.close();
            	context.term();
            	// open a socket connection remote user's client and send message.
//                Socket skt = new Socket(reg.getHost(),reg.getPort());
//                String completeMsg = "Message from " + this.regInfo.getUserName() + ": " + msg + "\n";
//                skt.getOutputStream().write(completeMsg.getBytes());
//                skt.close();
            } catch (Exception e) {
                // hmmm, user was registered, but it looks like they suddenly went away.
                //e.printStackTrace();
                retval = false;
            }
        }
        return retval;
    }

    private void promptUser()
    {
    	if(this.regInfo.getStatus()) {
    		System.out.println(this.regInfo.getUserName() + ": Enter command (friends, chat, broadcast, busy, or exit):");
    	} else {
    		System.out.println(this.regInfo.getUserName() + ": Enter command (friends, chat, broadcast, available, or exit):");
    	}
    }
    /**
     * Simple inner class that implements the thread that will be responsible
     * for handling incoming chat messages.
     */
    class SvrThread implements Runnable
    {
        // We'll use this to flag when the thread can stop accepting new
        // connections and exit.
        boolean done = false;

        /**
         * Thread's entry point.
         */
        public void run() {
        	try {
        		
        		String myHost = InetAddress.getLocalHost().getHostName();
                socket.bind("tcp://"+myHost+":"+Integer.toString(ChatClient.this.port));
                while(!Thread.currentThread().isInterrupted()) {
                	byte[] message = socket.recv(0);
                    System.out.println(new String(message, ZMQ.CHARSET));

                    // We'll refresh the prompt, lest the chimp on the console
                    // get's confused.
                        	
	                ChatClient.this.promptUser();
//                    clientSocket.close();
                }
            }catch (Exception e){
                System.out.println("Warning: caught Exception while running server socket.");
            }
            System.out.println("Server thread is exiting.");

            // ok, we're outta here.  Turn the lights out before you leave.
            
            ChatClient.this.socket.close();
        }
        

        /**
         * This is how we signal the ServerSocket thread which is likely to
         * be happily camped out on an accept() when the chimp types exit.
         */
        public void stop()
        {
            // set done to true.
            done = true;

            //
            // Just in case svr thread is blocked on accept, we give it a nudge.
            //
            Socket skt;
            try  {
                skt = new Socket(InetAddress.getLocalHost(),ChatClient.this.regInfo.getPort());
                skt.close();
            } catch (Exception e) {
            }
        }
    }

    /**
     * Main routine for client process.
     */
    public static void main(String[] args)
    {
        if(args.length == 0) {
            System.out.println("Usage: \n\tjava ChatClient userName [host[:port]]\n");
            System.out.println("\nwhere userName is your username, and host/port is the name service.");
            System.exit(0);
        }

        // Create a client object.
        ChatClient myClient;
        if(args.length > 1) {
            myClient = new ChatClient(args[0], args[1]);
        } else {
            myClient = new ChatClient(args[0],null);
        }

        // Now we will process chat commands.
        myClient.runCmdShell();
    }
}
