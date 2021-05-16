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
package com.pikatimer.transmitter.remote;

import com.pikatimer.transmitter.rfid.Command;
import com.pikatimer.transmitter.rfid.Status;
import com.pikatimer.transmitter.rfid.TimingData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;


/**
 *
 * @author john
 */
public class Uploader {
    static final Logger logger = LoggerFactory.getLogger(Uploader.class);
    private static final BlockingQueue<TimingData> timingDataQueue = new ArrayBlockingQueue(100000);
    private String endpoint = "UNSET";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

/**
    * SingletonHolder is loaded on the first execution of Singleton.getInstance() 
    * or the first access to SingletonHolder.INSTANCE, not before.
    */
    private static class SingletonHolder { 
            private static final Uploader INSTANCE = new Uploader();
    }

    public static Uploader getInstance() {
        
            return SingletonHolder.INSTANCE;
    }
    
    // private to prevent folks grabbig it via something 
    // other than the getInstance() method
    private  Uploader(){
      
    }
    
    public void startTransmitter(String e){
        // Append a slash if missing
        endpoint = e.endsWith("/") ? e : e + "/";
        
        // start the processing queue 
        logger.info("Starting Transmitter listener...");
        Thread dataTransmitter = new Thread("Data Transmitter Thread") {
            @Override public void run() {            
            
                while(true) {
                    Set<TimingData> newData = new HashSet();
                    Boolean uploadSuccess = false; 
                    try {
                        newData.add(timingDataQueue.take());
                        
                        // pause for 5 seconds to batch up the data transmissions
                        Thread.sleep(5000);
                        timingDataQueue.drainTo(newData);
                        
                        JSONArray dataArray = new JSONArray();
                        newData.forEach(d -> {
                            JSONObject t = new JSONObject();
                            t.put("mac", d.mac);
                            t.put("chip", d.chip);
                            t.put("timestamp", d.timestamp);
                            t.put("logNo",d.logNo);
                            t.put("reader",d.reader);
                            t.put("port",d.port);
                            dataArray.put(t.toString(4));
                        });
                        
                        HttpRequest request = HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(dataArray.toString(4)))
                        .uri(URI.create(endpoint + "data/"))
                        .setHeader("User-Agent", "Echo Transmitter") // add request header
                        .header("Content-Type", "application/json")
                        .build();

                        logger.trace("Posting to " + endpoint + "data/ : \n " + dataArray.toString(4));
                        HttpResponse<String> response;
                        try {
                            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() < 299) uploadSuccess=true;
                            
                            logger.trace("Data Response Code: " + Integer.toString(response.statusCode()));
                            logger.trace("Data Response Body: " + response.body());  
                        } catch (IOException | InterruptedException ex) {
                            logger.error(ex.getMessage());
                        }
                    } catch (InterruptedException | JSONException e) {
                        logger.error(e.getMessage());
                    }
                    
                    // If the upload fails, then put the data back
                    // and we can try it again. 
                    if (!uploadSuccess) {
                        timingDataQueue.addAll(newData);
                    }
                }
            }; 
        };
        
        dataTransmitter.setDaemon(true);
        dataTransmitter.setName("Connection to " + endpoint);
        dataTransmitter.start();
    }
    
    
    public void postData(TimingData d){
        logger.trace("Transmitter time data recieved from " + d.mac + " chip: " + d.chip + " time: " + d.timestamp);
        timingDataQueue.add(d);
    }
    

    // Post the status, check the reutrn for any pending commands
    public Optional<List<Command>> postStatus(Status s){
        
        List<Command> commands = new ArrayList();
        
        JSONObject status = new JSONObject();
        status.put("reading", s.reading);
        status.put("battery", s.battery);
        status.put("mac", s.mac);

        logger.trace("Posting to " + endpoint + "status/ : \n " + status.toString(4));

        HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(status.toString()))
                .uri(URI.create(endpoint + "status/"))
                .setHeader("User-Agent", "Echo Transmitter") // add request header
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // print status code
            logger.trace("Transmitter::postStatus Response Code: " + Integer.toString(response.statusCode()));
            logger.trace("Transmitter::postStatus Response Body: " + response.body());    
            // convert the response body into the command array
            JSONArray results = new JSONObject(response.body()).getJSONArray("Commands");
            for (int j = 0; j < results.length(); j++) {
                JSONObject p = results.getJSONObject(j);
                Command c = new Command();
                c.id = p.getString("id");
                c.cmd = p.getString("command");
                commands.add(c);
                logger.debug("Transmitter::postStatus COMMAND: " + c.id + " -> " + c.cmd);
            }
        } catch (IOException | InterruptedException ex) {
            logger.error(ex.getMessage());
        }
        return commands.isEmpty()?Optional.empty():Optional.of(commands);
    }
    
    public void ackCommand(String mac, String id) {
        JSONObject status = new JSONObject();
        status.put("completed", id);
        status.put("mac",mac);

        logger.debug("Transmitter::ackCommand mac: " + mac + " Command ID: " + id);
        HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(status.toString()))
                .uri(URI.create(endpoint + "command/"))
                .setHeader("User-Agent", "Echo Transmitter") 
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.trace("Transmitter::ackCommand Response Code: " + Integer.toString(response.statusCode()));
            logger.trace("Transmitter::ackCommand Response Body: " + response.body());   
        } catch (IOException | InterruptedException ex) {
            logger.error(ex.getMessage());
        }

    }
}
