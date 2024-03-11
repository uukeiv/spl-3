package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.GlobalData;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;


public class TftpProtocol implements BidiMessagingProtocol<byte[]>  {

    private int connectionId;
    private Connections<byte[]> conns;
    private boolean terminate        = false;
    private boolean connected        = false;
    private boolean reading          = false;
    private String fileToWrite       = null;
    private String fileToRead        = null;
    private byte[] remainingFileName = null;
    private final String path        = "Flies\\";
    private short opcode             = 0;
    private short lastBlock          = 0;
    private short readBlock          = 0;
    private int fileStopped          = 0;
    private short nameBlockNum       = 0;
    private int maxDataPack          = 512;
    //
    static final short RRQ   = 0x01;
    static final short WRQ   = 0x02;
    static final short DATA  = 0x03;
    static final short ACK   = 0x04;
    static final short ERROR = 0x05;
    static final short DIRQ  = 0x06;
    static final short LOGRQ = 0x07;
    static final short DELRQ = 0x08;
    static final short BCAST = 0x09;
    static final short DISC  = 0x0a;
    //

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {
        this.connectionId = connectionId;
        this.conns = connections;

    }

    @Override
    public void process(byte[] message) {
        System.out.println("received " + Arrays.toString(message) + "which is the length of " + message.length);
        opcode = arrToShort(message);
        if (opcode == LOGRQ){
            login(message);
            return;
        }

        if (opcode == DISC){
            disconnect();
            return;
        }
        // checks if the user has logged in
        if (!connected)
            conns.send(connectionId, error((short) 6));
        else
            classify(opcode, message);
    }

    @Override
    public boolean shouldTerminate() {

        return terminate; 
    } 

    private void classify(short opcode, byte[] message){
        switch (opcode) {
            case RRQ:
                readFile(message);
                break;
            
            case WRQ:
                writeFileCheck(message);
                break;

            // this case only catches the wrtie file data
            case DATA:
                writeFile(message);
                break;    
            
            // this gets called from reading and get dir so i need to add some sort of salution for this 
            case ACK:{
                if (reading)
                    continueReading();
                else   
                    continueFilesName();
                break;
            }
            // case ERROR:
                
            //     break;

            case DIRQ:
                sendFileNames();
                break;   
            
            case DELRQ:
                deleteFile(message);
                break;
            
            default:
                undefined();
                break;
        }
    }

    private void login(byte[] message){
        if (connected){
            conns.send(connectionId,error((short)6));// sends back error
            return;
        }

        String name = new String(Arrays.copyOfRange(message, 2, message.length));
        /// checks if this name is unique 
        for (Map.Entry<Integer, String> entry : GlobalData.ids.entrySet()) {
            if (name.equals(entry.getValue())) {
                conns.send(connectionId, error((short) 0)); // name is not unique
                return; // Exit the loop early
            }
        }
        // sends back response
        connected = true;
        GlobalData.ids.put(connectionId, name);
        conns.send(connectionId, sendAck((short)0));
    }

    private byte[] error(short type){
        byte[] errMess = errorMessage(type).getBytes();
        byte[] error = new byte[errMess.length + 4];
        byte[] opcode = shortToArr(ERROR);
        byte [] errType = shortToArr(type);
        System.arraycopy(opcode, 0, error, 0, opcode.length);
        System.arraycopy(errType, 0, error, opcode.length, errType.length);
        System.arraycopy(errMess, 0, error, opcode.length + errType.length, errMess.length);
        return error;
    }

    private String errorMessage(short type){
        switch (type) {
            case 0:
                return "Unknown error";
            case 1:
                return "File not found";
            case 2:
                return "Access violation";
            case 3:
                return "Disk full or allocation exceeded";
            case 4:
                return "Illegal TFTP operation";
            case 5:
                return "File already exists";
            case 6:
                return "User not logged in";
            case 7:
                return "User already logged in";
            case 8:
                return "Wrong block Order";
        }

        return null;// never going to get here
    }
    
    private byte[] sendAck(short num){
        byte[] ackNum = shortToArr(num);
        byte[] opcode = new byte[]{0x00,0x04};
        byte[] pack   = new byte[opcode.length + ackNum.length];
        System.arraycopy(opcode, 0, pack, 0, opcode.length);
        System.arraycopy(ackNum, 0, pack, opcode.length, ackNum.length);
        return pack;
    }

    private void readFile(byte[] message){
        
        String fname = new String(Arrays.copyOfRange(message, 2, message.length));
        fileToRead =  path + fname;
        reading = true;
        readBlock = 0;
        continueReading();

    }

    private void continueReading(){
        try (FileInputStream fis = new FileInputStream(fileToRead)) {
            byte[] data = new byte[maxDataPack];
            int bytesRead;
            // Reads 512 chunk of bytes from the place he stoped
            fis.skip(readBlock*maxDataPack);
            readBlock++;

            bytesRead = fis.read(data);

            if (bytesRead == -1){
                reading = false;
                return;
            }
            if (bytesRead != maxDataPack)            
                data = Arrays.copyOf(data, bytesRead);
            conns.send(connectionId, dataPack(data,readBlock));
            
            
        }
        catch(FileNotFoundException err){
            // file doesnt exist
            conns.send(connectionId, error((short) 1));
            return;
        }
         catch (IOException e) {
        }
    }   

    private void writeFileCheck(byte[] message){
        lastBlock = 1;
        String fname = new String(Arrays.copyOfRange(message, 2, message.length));

        fileToWrite = path + fname;
        File file = new File(fileToWrite);

        // Checks if the file already exists
        if (file.exists()){
            conns.send(connectionId, error((short)5));
            return;
        }
        try{
            file.createNewFile();
        }
        catch(IOException e){}
        conns.send(connectionId,sendAck((short)0));
    }

    private void writeFile(byte[]message){

        // checks if the right block was received 
        byte[] blockNum = new byte[2];
        blockNum[0] = message[4];
        blockNum[1] = message[5];
        short messageNum = arrToShort(blockNum);
        if (lastBlock != messageNum){
            conns.send(connectionId, error((short) 8));
            return;
        }

        // gets the length of the data
        byte[] dataSize = new byte[2];
        dataSize[0] = message[2];
        dataSize[1] = message[3];
        short len = arrToShort(dataSize);

        // extracts the actual data
        byte[] toWrite = new byte[len]; 
        System.arraycopy(message, 6, toWrite, 0, message.length - 6);;

        // Writes bytes to the file
        try (FileOutputStream fos = new FileOutputStream(fileToWrite, true)) {
            
            fos.write(toWrite);
        } catch (IOException e) {
            // need to check this 
            if (e.getMessage() == "No space left on device")
                conns.send(len, error((short) 3));
            return;
        }

        // sends back a ACK packet 
        conns.send(connectionId,sendAck(messageNum));
        lastBlock++;

        // technically this might bug out but there is no way of telling if the file transfer has finished if the files size
        // happens to be exacly a multiplication of 512
        if (len != maxDataPack){
            GlobalData.ids.forEach((id,value) -> {
                if (value != "")
                    conns.send(id, bCastPack(true, fileToWrite.substring(path.length())));// sends the file name without the path
            });
        }
    }

    private void sendFileNames(){
        fileStopped = 0;
        nameBlockNum = 1;
        continueFilesName();
    }

    private void continueFilesName(){
        // creates a File object representing the directory
        File directory = new File(path);

        // gets all the file names in the directory
        String[] files = directory.list();
        // all the files names have been sent
        if ( fileStopped >= files.length)
            return;
        byte[] data = new byte[maxDataPack];
        int indx = 0;
       
        // if a file was stopped half way through continue it
        if (remainingFileName != null){
            for (int j = 0; j < remainingFileName.length && indx < maxDataPack; j++, indx++)
                data[indx] = remainingFileName[j];
            
            // still didnt finish reading this file name
            if ( indx == maxDataPack)
                remainingFileName = Arrays.copyOfRange(remainingFileName, remainingFileName.length - indx, remainingFileName.length);          
            else{//finished
                data[indx] = '\0';
                indx++;
                fileStopped++;
                remainingFileName = null;
            }
        }

        // sends all the file names to the client
        if (files != null && indx != maxDataPack && fileStopped != files.length) {
            // runs on all the names
            for (int i = fileStopped; i < files.length && indx < maxDataPack; i++) {
                byte[] name = files[i].getBytes();
                System.arraycopy(name, 0, data, indx, name.length);
                indx += name.length;
                // a name is cut off
                if (indx >= maxDataPack){
                    int left = indx - maxDataPack;
                    remainingFileName = Arrays.copyOfRange(name, name.length - left, name.length);
                    indx = maxDataPack;
                }
                // add delimiter
                else{
                    data[indx] = '\0';
                    indx++;
                    fileStopped++;
                }
            }
        }
        
        if (indx != 0)
            conns.send(connectionId, dataPack(Arrays.copyOf(data, indx), nameBlockNum));
        else
            conns.send(connectionId, dataPack(new byte[0], nameBlockNum));// just send one data packet with only the delimiter 
        
        nameBlockNum++;
    }

    private void deleteFile(byte[] message){
        // extracts the name
        String fname = new String(Arrays.copyOfRange(message, 2, message.length));
        
        // creates a File object representing the file
        File file = new File(path + fname);

        // checks if the file exists
        if (file.exists()) {
            // deletes the file
            boolean deleted = file.delete();
            if (deleted) {
                conns.send(connectionId,sendAck((short) 0));
                GlobalData.ids.forEach((id,value) -> {
                    if (value != "")
                        conns.send(id, bCastPack(false, fname));
                });
            } else 
                conns.send(connectionId, error((short) 0));
            
        } else 
            conns.send(connectionId, error((short) 1));
    }

    private void disconnect(){
        if (connected)
            conns.send(connectionId, sendAck((short)0));
        conns.disconnect(connectionId);
        terminate = true;
    }

    private byte[] dataPack(byte[] data, short num){
        short len       = (short)(data.length);
        byte[] packLen  = shortToArr(len);  
        byte[] opcode   = shortToArr(DATA);
        byte[] blockNum = shortToArr(num); 
        byte[] pack     = new byte[len + 6];
        System.arraycopy(opcode, 0, pack, 0, opcode.length);
        System.arraycopy(packLen, 0, pack, opcode.length, packLen.length);
        System.arraycopy(blockNum, 0, pack, opcode.length + packLen.length, blockNum.length);
        System.arraycopy(data, 0, pack, opcode.length + packLen.length + blockNum.length, data.length);
        return pack;
    }

    private byte[] bCastPack(boolean added, String fileName){
        byte[] pack   = new byte[fileName.length() + 3];
        byte[] opcode = shortToArr(BCAST);
        byte[] name   = fileName.getBytes();
        System.arraycopy(opcode, 0, pack, 0, opcode.length);
        if (added)
            pack[2] = 0x01;
        else
            pack[2] = 0x00;
        System.arraycopy(name, 0, pack, 3, name.length);
        return pack;
        
    }

    private short arrToShort(byte[] arr){
        return ( short ) ((( short ) arr [0]) << 8 | ( short ) ( arr [1] & 0xff) );
    }

    private byte[] shortToArr(short num){
        return new byte []{( byte ) (num >> 8) , ( byte ) (num & 0xff)};
    }

    private void undefined(){
        conns.send(connectionId, error((short) 4));
    }
}
