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
package com.pikatimer.transmitter.rfid;

import com.pikatimer.transmitter.remote.Uploader;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



// TODO:
// Auto-Rewind
// Reading Status query
// Command injector
// Post status after read status query returns
// Fix date formatter (always include sec and mili)


/**
 *
 * @author john
 */
public class RFIDConnector {
    LocalDateTime EPOC = LocalDateTime.of(LocalDate.parse("1980-01-01",DateTimeFormatter.ISO_LOCAL_DATE),LocalTime.MIDNIGHT);
    static final Logger logger = LoggerFactory.getLogger(RFIDConnector.class);

    Uploader uploader = Uploader.getInstance();
    String voltageStatus = "";
    Integer lastReadLogNo = -1;
    DataOutputStream readerOuputStream = null;
    Semaphore okToSend = new Semaphore(1);
    private static final BlockingQueue<String> commandResultQueue = new ArrayBlockingQueue(10);
    
    private static final Set<String> previousCommands = new HashSet();

    private String mac;

    public RFIDConnector() {

    }

   
    
    public Thread connect(String m, String readerIP,String readerType ){
        mac = m;
        logger.info("Connecting to " + readerType + " " + mac + "(" + readerIP + ")");
               
        Thread readerConnection = new Thread("Connection to " + mac) {
            @Override public void run() {
                Boolean readRetry = false;
                Boolean connectToUltra = true;

                while(connectToUltra) {
                    try (
                        Socket ultraSocket = new Socket(readerIP, 23); 
                        InputStream input = ultraSocket.getInputStream();
                        OutputStream rawOutput = ultraSocket.getOutputStream();
                    ) {
                        connectToUltra = true; // we got here so we have a good connection
                        ultraSocket.setSoTimeout(15000); // 15 seconds. In theory we get a voltage every 2
                        readerOuputStream = new DataOutputStream(new BufferedOutputStream(rawOutput));

                        int read = -255; 
                        String line = "";
                        while (read != 10) { // 1,Connected,<stuff>\n is sent on initial connect. 10 == \n
                            read = input.read();
                            line = line +  Character.toString ((char) read);
                            //logger.trace("Read: " + Character.toString ((char) read) + "  " + Integer.toHexString(0x100 | read).substring(1));
                        } 
                        logger.trace("Read connect string for " + mac + ": " + line);

                        while(connectToUltra) {
                            read = -255; 
                            line = "";
                            try {
                                while (read != 10 && connectToUltra) {
                                    read = input.read();
                                    readRetry = false;
                                    if (read == -1) {
                                        connectToUltra = false;
                                        logger.trace("End of stream!" + Integer.toHexString(read));
                                    } if (read != 10) {
                                        line = line +  Character.toString ((char) read);
                                        //logger.trace("Read: " + Character.toString ((char) read) + "  " + Integer.toHexString(0x100 | read).substring(1));
                                    } else {
                                        processLine(line);
                                    }
                                }
                            } catch(java.net.SocketTimeoutException e){
                                logger.warn("Socket Timeout Exception...");
                                if (readRetry) {
                                    logger.warn("...2nd One in a row so we will bail");
                                    throw e;
                                } else {
                                    logger.warn("...First one so let's ask for status");
                                    readRetry=true;
                                    //getReadStatus();
                                }
                            }
                        }
                    } catch (Exception e) {
                       // e.printStackTrace();
                        if (connectToUltra){ 
                            connectToUltra = false;
                            logger.warn("RFIDDirectReader Connection Exception: " + e.getMessage());
                        }
                    } finally {
                        logger.warn("Disconnecting from " + mac);
                            connectToUltra = false;
                    }
                }
            }; 
        };
        
        readerConnection.setDaemon(true);
        readerConnection.setName("Connection to " + mac);
        readerConnection.start();
        
        return readerConnection;
    }
    
    private void processLine(String line) {
        logger.trace(mac + " Read Line: " + line);
        
        String type = "unknown";
        if (line.startsWith("0,")) type="chip";
        else if (line.startsWith("1,")) type="chip";
        else if (line.startsWith("V")) type="voltage";
        else if (line.startsWith("S")) type="status";
        else if (line.startsWith("U")) type="command";
        else if (line.startsWith("u")) type="command"; // general command
        else if (line.substring(0,8).matches("^\\d+:\\d+:\\d+.*")) type = "time"; //time ends with a special char

        switch(type){
            case "chip":
                processRead(line);
                break;
            case "status": // status 
                logger.trace(mac + " Read Status: " + line);
                commandResultQueue.offer(line);
                break;
            
            case "voltage": // voltage
                //logger.trace(mac + " Voltage: " + line);
                voltageStatus = line.split("=")[1];
                logger.trace(mac + " Voltage: " + voltageStatus);
                getReadStatus();
                break;
            case "time": // command response
                logger.trace(mac + " Time: " + line.substring(0,19));
                //commandResultQueue.offer(line.substring(0,19));
                break;
            case "command":// command response
                logger.trace(mac + " Command response recieved");
                commandResultQueue.offer(line);
                break;
            default: // unknown command response
                logger.trace(mac + " Unknown: \"" + line.substring(0, 1) + "\" " + line);
        }

    }
    
    
    private void processRead(String r){
        System.out.println("Chip Read: " + r);
        String[] tokens = r.split(",", -1);
        // 0,11055,1170518701,698,1,-71,0,2,1,0000000000000000,0,29319
        // 0 -- junk
        // 1 -- chip
        // 2 -- time
        // 3 -- milis
        // 4 -- antenna / port
        // 5 -- RSSI (signal strength)
        // 6 -- is Rewind? (0 is live, 1 is memorex)
        // 7 -- Reader A or B (1 or 2)
        // 8 -- UltraID (zeroes)
        // 9 -- MTB Downhill Start Time
        // 10 -- ???
        // 11 -- LogID
        
        if (tokens.length < 12 ) {
            System.out.println("  Chip read is missing data: " + r);
            return;
        }
        
        String chip = tokens[1];
        String port = tokens[4];
        String reader = tokens[7];
        //String antenna = tokens[x];
        //String rewind = tokens[x];
        String rewind = tokens[6];
        String logNo = tokens[11];
        
        //Auto-Rewind on missing data
        if (rewind.equals("0")) {
            
            
            int currentRead=Integer.parseInt(logNo);
            if (lastReadLogNo == -1 || lastReadLogNo + 1 == currentRead ) {
                //logger.trace("No missing reads: Last " + lastRead + " Current: " + logNo);
                lastReadLogNo = currentRead;
            } else {
                logger.warn("Missing a read: Last " + lastReadLogNo  + " Current: " + logNo);
                // auto-rewind
                rewind(lastReadLogNo ,currentRead);
                lastReadLogNo = currentRead;
            }
        }
        
        System.out.println("  Chip: " + chip + " logNo: " + logNo);
        
        // make sure we have what we need...
        if (port.equals("0") && ! chip.equals("0")) { // invalid combo
            System.out.println("Non Start time: " + chip);
            return;
        } else if (!port.matches("[1234]") && !chip.equals("0")){
            System.out.println("Invalid Port: " + port);
            return;
        }
        
        //LocalDate origin = LocalDate.parse("1980-01-01",DateTimeFormatter.ISO_LOCAL_DATE); 
        //LocalDateTime read_ldt = LocalDateTime.of(origin, LocalTime.MIDNIGHT);
        Long seconds = Long.parseLong(tokens[2]);
        Long millis = Long.parseLong(tokens[3]);
        LocalDateTime read_ldt = EPOC.plusSeconds(seconds).plusNanos(millis * 1000000);
        
        
        TimingData rawTime = new TimingData();
        rawTime.chip = chip;
        rawTime.logNo = logNo;
        rawTime.mac = mac;
        rawTime.port = port;
        rawTime.reader = reader;
        rawTime.timestamp =  read_ldt.toString(); // fix this to always be YYYY-MM-DD HH:MM:ss.SSS

        uploader.postData(rawTime); // process it
        
    }

    private void rewind(Integer lastRead, Integer currentRead) {
            //Auto-Rewind
    
        Thread autoRewindThread = new Thread("AutoRewind for " + mac) {
            @Override public void run() {
                if ( readerOuputStream != null) {
                    Boolean aquired = false;
                    try {
                        if (okToSend.tryAcquire(10, TimeUnit.SECONDS)){
                            aquired=true;
                            logger.info("AutoRewind for " + mac + " from " + lastRead + " to " + currentRead);

                            readerOuputStream.flush();

                            // Send 6[0x00][0x00], like RFIDServer, not "800" per the manual
                            String command = "6";
                            command += Character.toString ((char) 0) ;
                            command += Character.toString ((char) 0) ;
                            command += lastRead.toString() ;
                            command += Character.toString ((char) 13) ;
                            command += currentRead.toString();
                            command += Character.toString ((char) 13) ;

                            readerOuputStream.writeBytes(command);
                            readerOuputStream.flush();

                        } else {
                            // timeout
                            System.out.println("Timeout with AutoRewind command");
                        }
                    } catch (IOException | InterruptedException ex) {
                        logger.error("AutoRewind Failure: " + ex.getMessage());

                    } finally {
                        if (aquired) okToSend.release();
                    }
                }
            }
        };
        autoRewindThread.start();
    
    }

    private void getReadStatus() {
        
        logger.trace("getStatus Called: mac -> " + mac + " battery -> \"" + voltageStatus + "\"");
        // Send the read command
        Thread getReadStatusThread = new Thread("Read Status request for " + mac) {
            @Override public void run() {
                if (readerOuputStream != null) {
                    Boolean aquired = false;
                    try {
                        if (okToSend.tryAcquire(10, TimeUnit.SECONDS)){
                            aquired=true;
                            logger.trace("getReadStatus(): Sending ? command");
                            readerOuputStream.writeBytes("?");
                            readerOuputStream.flush();
                            
                            Status s = new Status();
                            s.mac = mac;
                            s.battery = Integer.parseInt(voltageStatus);
                            s.reading = false;

                            try {
                                String result = commandResultQueue.poll(10, TimeUnit.SECONDS);
                                if (result != null) {
                                    logger.trace("Reading Status : " + result);
                                    if (result.substring(2, 3).startsWith("1")) s.reading = true;
                                    Optional<List<Command>> commands = uploader.postStatus(s);

                                    // If there are any commands, then execute them.... 
                                    if(commands.isPresent()){
                                        processCommands(commands.get());
                                    }
                                } else {
                                // timeout
                                    logger.error("Timeout waiting for status for " + mac);
                                }
                            } catch (InterruptedException ex) {
                                logger.error("InterruptedException " + mac + " " + ex.getLocalizedMessage());
                            }
                        } else {
                            // timeout
                            System.out.println("Timeout with read status request.");
                        }
                    } catch (IOException | InterruptedException ex) {
                        logger.error("ReadStatus Failure: " + ex.getMessage());

                    } finally {
                        if (aquired) okToSend.release();
                    }
                }
            }
        };
        getReadStatusThread.start();
        
    }
    
    private void processCommands(List<Command> commands){
        // Possible commands are
        //      rewind <from>
        //      rewind <from> <to>
        //      start reading 
        //      stop reading
        commands.forEach(c -> {
            if (previousCommands.contains(c.id)) return;
            previousCommands.add(c.id);
            
            // Run it
            try {
                if (c.cmd.startsWith("START")) {
                    readerOuputStream.writeBytes("R");
                    readerOuputStream.flush();
                } else if (c.cmd.startsWith("STOP")){
                    readerOuputStream.writeBytes("S");
                    readerOuputStream.flush();
                    readerOuputStream.writeBytes("N");
                    readerOuputStream.flush();
                } else if (c.cmd.startsWith("REWIND")){
                    
                    // Split the command 
                    String[] cmd = c.cmd.split(" ");

                    // Default to today 
                    Long startTimestamp = Duration.between(EPOC, LocalDateTime.of(LocalDate.now(), LocalTime.MIN)).getSeconds();
                    Long endTimestamp = Duration.between(EPOC, LocalDateTime.of(LocalDate.now(), LocalTime.MAX)).getSeconds();
                    
                    // Parse the command to snag the start / end timestamps and replace what is above. 
                    if (cmd.length > 1) startTimestamp = Long.parseLong(cmd[1]);
                    if (cmd.length > 2) endTimestamp = Long.parseLong(cmd[2]);

                    logger.debug("Issuring Rewind for " + mac + " From: " + startTimestamp + " To: " + endTimestamp);
                    readerOuputStream.flush();

                    // Send 8[0x00][0x00], like RFIDServer, not "800" per the manual
                    String command = "8";
                    command += Character.toString ((char) 0) ;
                    command += Character.toString ((char) 0) ;
                    command += startTimestamp.toString() ;
                    command += Character.toString ((char) 13) ;
                    command += endTimestamp.toString();
                    command += Character.toString ((char) 13) ;

                    readerOuputStream.writeBytes(command);
                    readerOuputStream.flush();
                }
                // Ack it
                uploader.ackCommand(mac, c.id);
            } catch (IOException ex){
                logger.error("Command Exception " + mac + " " + ex.getLocalizedMessage());
            }
        });
    }
    
}
