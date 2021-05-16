# Echo Transmitter: 
A (relatively) simple way to relay RFID reader data over the internet. 

## Downloads
To download, go to https://github.com/PikaTimer/EchoTransmitter/releases

## Requirements

Requires a Java JRE Runtime 11.0 or newer. See https://jdk.java.net/16/ for the current JDK 16 release for Windows, Linux, and MacOS.



## Usage
To run, execute the following on a command line: 
'java -jar transmitter.jar <Echo Server>'

Replace `<echo Server>` with the url of your echo server. See https://github.com/PikaTimer/EchoServer/releases for how you can host your own server

The system will automatically scan the local subnet for any RFID Readers and then relay the data to the Echo Server. 


