/*
 * Copyright (C) 2021 John Garner
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

import com.pikatimer.transmitter.remote.Uploader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author john
 */
public class Transmitter {
    static final Logger logger = LoggerFactory.getLogger(Transmitter.class); 
    static final ReaderScanner readerScanner = new ReaderScanner();
    static final Uploader uploader = Uploader.getInstance();
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {

        System.out.println("Starting the Tramsmitter....");
        //System.out.println("Press Q + ENTER to terminate");
        System.out.println("Press CTRL-C to terminate");
        System.out.println("\n\n\n");
        
        

        // Snag the remote endpoint from the command line
        // and start the transmitter
        String endpoint = "";
        if(args.length >= 1) endpoint = args[0];
        
        System.out.println("Connecting to " + endpoint);
   
        if (! endpoint.isEmpty()) {
            
            // Validate the endpoint
            
            
            // start the scanner
            Thread scanner = readerScanner.startScanner();
            uploader.startTransmitter(endpoint);

            // Run until something stops us... 
            try {
                scanner.join();
            } catch (InterruptedException ex) { }
            
        } else {
            System.out.println("Endpoint Not Specified!");
            System.out.println("Example: java -jar transmitter.jar https://your.server.com/echo/ ");
        }
            
        logger.info("Shutting down Chirper....");
        // This works for NetBeans where sending a
        // ctrl-c is a pain but not so much 
        // when nohup'ed on a linux system
//        try (Scanner s = new Scanner(System.in)) {
//            String whatever = s.next();
//            logger.info("Shutting down Chirper....");
//        }
    }
    
}
