import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.IOException;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.CancelledKeyException;

import java.security.PrivilegedAction;

import java.util.Iterator;
import java.util.HashMap;
import java.util.Properties;
import java.util.List;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

public class NIOServer {
  // selection key => handler map (start with Integer; later use callbacks of some kind.
  static HashMap<SelectionKey,Integer> clientToHandler;
  static HashMap<SelectionKey,LinkedList<String>> inbox;
  
  public static void ShowClients() {
    System.out.println("===<current clients>===");
    for (SelectionKey each : clientToHandler.keySet()) {
      Integer eachHandler;
      if ((eachHandler = clientToHandler.get(each)) != null) {
        System.out.println("key : " + each  + " => client handler: " + eachHandler);
      }
    }
    System.out.println("===</current clients>===");
  }

  public static void main(String[] args) 
    throws IOException {
    // Obtain the command-line arguments and parse the port number
    
    if (args.length != 1) {
      System.err.println("Usage: java <options> NIOServer <localPort>");
      System.exit(-1);
    }
    
    // 3. Main Loop: handle connections from network clients.
    // 3.1. Startup service network connection.
    int localPort = Integer.parseInt(args[0]);

    // <1. NIO>
    Selector selector = SelectorProvider.provider().openSelector();
    
    // Create a new non-blocking server socket channel
    ServerSocketChannel serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    
    // Bind the server socket to the specified address and port
    InetSocketAddress isa = new InetSocketAddress("localhost",localPort);
    serverChannel.socket().bind(isa);
    
    // Register the server socket channel, indicating an interest in 
    // accepting new connections
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    // </1. NIO>

    // selection key => handler map (start with Integer; later use callbacks of some kind.
    clientToHandler = new HashMap<SelectionKey,Integer>();
    inbox = new HashMap<SelectionKey,LinkedList<String>>();
    

    Integer clientSerialNum = 0;
    String clientMessage = "";

    System.out.println("start main listen loop..");
    while(true) {

      selector.select();

      Iterator selectedKeys = selector.selectedKeys().iterator();
      while (selectedKeys.hasNext()) {
        final SelectionKey sk = (SelectionKey) selectedKeys.next();
        selectedKeys.remove();

        if (!sk.isValid()) {
          System.out.println("key is not valid; continuing.");
          continue;
        }
        
        // Check what event is available and deal with it.
        if (sk.isAcceptable()) {
          System.out.println("Accepting connection from client with accept key : " + sk);

          ShowClients();

          // For an accept to be pending the channel must be a server socket channel.
          ServerSocketChannel serverSocketChannel = (ServerSocketChannel) sk.channel();

          
          // Accept the connection and make it non-blocking
          SocketChannel socketChannel = serverSocketChannel.accept();
          socketChannel.configureBlocking(false);
          
          // Register the new SocketChannel with our Selector, indicating
          // we'd like to be notified when there's data waiting to be read
          socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
          

        } else { 
          if (sk.isReadable()) {

            if (clientToHandler.get(sk) == null) {
              clientToHandler.put(sk,clientSerialNum++);
            }

            // initialize message queue if necessary.
            if (inbox.get(sk) == null) {
              inbox.put(sk,new LinkedList<String>());
            }
            
            System.out.println("Reading input from client #" + clientToHandler.get(sk));
            
            final SocketChannel socketChannel = (SocketChannel) sk.channel();
            
            ByteBuffer readBuffer = ByteBuffer.allocate(8192);
            readBuffer.clear();
            
            // Attempt to read from the client.
            int numRead = 0;
            try {
              numRead = socketChannel.read(readBuffer);
              if (numRead != -1) {
                readBuffer.flip();
                System.out.println("read: " + numRead + " bytes.");
                byte[] bytes = new byte[8192];
                readBuffer.get(bytes,0,numRead);
                Hexdump.hexdump(System.out,bytes,0,numRead);
                ShowClients();

                // Broadcast this client's message to all (other) clients.
                String nickName = "client " + clientToHandler.get(sk);
                String message = nickName + " said: " + new String(bytes);
                for (List<String> each : inbox.values()) {
                  
                  // Don't loop back a message to the same client
                  // sending the message: no point in doing that.
                  if (each != inbox.get(sk)) {
                    each.add(message);
                  }

                }

                clientMessage = new String(bytes);
              }
            } catch (IOException e) {
              System.err.println("IOEXCEPTION: GIVING UP ON THIS CLIENT.");
              // The remote forcibly closed the connection, cancel
              // the selection key and close the channel.
              clientToHandler.remove(sk);
              sk.cancel();
              try {
                sk.channel().close();
              }
              catch (IOException ioe) {
                System.err.println("IoException trying to close socket.");
                ioe.printStackTrace();
              }
            }
            
            if (numRead == -1) {
              // Remote entity shut the socket down cleanly. Do the
              // same from our end and cancel the channel.
              
              System.out.println("Nothing left to read from client. Closing client connection: " + clientToHandler.get(sk));
              try {
                clientToHandler.remove(sk);
                sk.channel().close();
                
                // dump current client->context mapping to console.
                ShowClients();
                
              }
              catch (IOException ioe) {
                System.err.println("IoException trying to close socket.");
                ioe.printStackTrace();
              }                            
              sk.cancel();
            }
          }
          
          try {
            if (sk.isWritable()) {

              // initialize message queue for this client if necessary.
              if (inbox.get(sk) == null) {
                inbox.put(sk,new LinkedList<String>());
              }

              // check inbox queue for this client: send all messages in queue.
              try {
                while(true) {
                  String messageForClient = inbox.get(sk).removeFirst();

                  ByteBuffer writeBuffer = ByteBuffer.allocate(8192);
                  writeBuffer.clear();
                  
                  System.out.println("writing message: " + messageForClient + " to client: " + clientToHandler.get(sk));
                
                  writeBuffer.put(messageForClient.getBytes(),0,8192);
                  writeBuffer.flip();
                  ((SocketChannel)sk.channel()).write(writeBuffer);
                }
              }
              catch (NoSuchElementException e) {
                // done writing to this client.
              }
            }
          }
          catch (CancelledKeyException e) {
            System.out.println("CancelledKeyException: maybe client closed.");
            e.printStackTrace();

            // clean up data structures.
            System.out.println("Cancelled Key: closing client connection: " + clientToHandler.get(sk));
            try {
              clientToHandler.remove(sk);
              sk.channel().close();
              
              // dump current client->context mapping to console.
              ShowClients();
              
            }
            catch (IOException ioe) {
              System.err.println("IoException trying to close socket.");
              ioe.printStackTrace();
            }                            
            sk.cancel();
          }
          
        }
      }
    }
  }
}

