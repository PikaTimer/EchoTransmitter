/*
 * Copyright (C) 2021 john
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pikatimer.transmitter;

import com.pikatimer.transmitter.rfid.RFIDConnector;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */
public class ReaderScanner {
    static final Logger logger = LoggerFactory.getLogger(ReaderScanner.class);
    static final Map<String,Thread> readerConnections = new HashMap();
        
        
    public Thread startScanner() {
        logger.info("Starting Reader Scanner...");
        Thread scanner = new Thread("Reader Scanner") {
                @Override public void run() {
                    
                    // This is ugly but it works
                    byte one = Integer.valueOf(1).byteValue();
                    byte zero = Integer.valueOf(0).byteValue();
                    byte[] packetData = {one,zero,zero,zero,zero,zero,zero,zero};
                    
                    // Find the server using UDP broadcast
                    // Loop while the app is running
                    // UDP Broadcast code borrowed from https://demey.io/network-discovery-using-udp-broadcast/
                    // with a few modifications to protect the guilty and to bring it up to date
                    // (e.g., try-with-resources)
                    while (true) {
                        try(DatagramSocket broadcastSocket = new DatagramSocket()) {
                            broadcastSocket.setBroadcast(true);
                            // 2 second timeout for responses
                            broadcastSocket.setSoTimeout(10000);
                            
                            // Send a packet to 255.255.255.255 on port 2000
                            DatagramPacket probeDatagramPacket = new DatagramPacket(packetData, packetData.length, InetAddress.getByName("255.255.255.255"), 2000);
                            broadcastSocket.send(probeDatagramPacket);
                            
                            logger.trace("Sent UDP Broadcast to 255.255.255.255");
                            // Broadcast the message over all the network interfaces
                            
                            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                            while (interfaces.hasMoreElements()) {
                              NetworkInterface networkInterface = interfaces.nextElement();

                              if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                                continue; // Don't want to broadcast to the loopback interface
                              }

                              for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                                InetAddress broadcast = interfaceAddress.getBroadcast();
                                if (broadcast == null) {
                                  continue;
                                }
                                // Send the broadcast package!
                                try {
                                  DatagramPacket sendPacket = new DatagramPacket(packetData, packetData.length, broadcast, 8888);
                                  broadcastSocket.send(sendPacket);
                                  logger.trace("Sent UDP Broadcast to " + broadcast.getHostAddress());
                                } catch (Exception e) {
                                }
                              }
                            }


                            //Wait for a response
                            try {
                                while (true) { // the socket timeout should stop this
                                    byte[] recvBuf = new byte[1500]; // mass overkill
                                    DatagramPacket receivePacket = new DatagramPacket(recvBuf, recvBuf.length);
                                    broadcastSocket.receive(receivePacket);
                                    
                                    
                                    String message = new String(receivePacket.getData()).trim();
                                    
                                    logger.debug("Ultra Finder Response: " + receivePacket.getAddress().getHostAddress() + " " + message);
                                    
                                    // parse the response string
                                    
                                    // Set the Ultra's IP
                                    String readerIP = receivePacket.getAddress().getHostAddress().toString();
                                    
                                    // Now the Type
                                    String readerType = "UNKNOWN";
                                    if (message.matches("(?i:.*JOEY.*)")) readerType = "Joey";
                                    if (message.matches("(?i:.*Ultra.*)")) readerType = "Ultra";
                                    
                                    // Finally the Rabbit MAC
                                    // dump the first 3 octets and save the last 3
                                    String mac = message.substring(message.indexOf(":")+7).toUpperCase( );
                                    
                                    if (! readerConnections.containsKey(mac) || ! readerConnections.get(mac).isAlive()){
                                        RFIDConnector reader = new RFIDConnector();
                                        readerConnections.put(mac,reader.connect(mac,readerIP,readerType));
                                    }
                                }
                            }catch (Exception ex){
                            }
                                                 
                        } catch (IOException ex) {
                          //Logger.getLogger(this.class.getName()).log(Level.SEVERE, null, ex);
                          logger.debug("Scanner Thread Exception: " + ex.getMessage());
                        }
                    }
                }
        };
        
        scanner.setDaemon(true);
        scanner.setName("Reader Scanner");
        scanner.start();
        
        return scanner;
    }
}
