# Echo Transmitter: A simple way to relay RFID reader data over the internet. 

## Downloads
To download, go to https://github.com/PikaTimer/EchoTransmitter/releases

## Requirements and Usage

Requires a Java JRE Runtime 11 or newer.

To run, execute the following: 
'java -jar transmitter.jar <Echo Server>`

Replace `<echo Server>` with the url of your echo server. See https://github.com/PikaTimer/EchoServer/releases for how you can host your own server

The system will automatically scan the local subnet for any RFID Readers and then relay the data to the Echo Server


