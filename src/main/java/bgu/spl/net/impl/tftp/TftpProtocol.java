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
    private String fileToWrite       = null;
    private byte fileData[];
    private final String path        = "Flies\\";
    private short opcode             = 0;
    private int maxDataPack          = 512;
    private int reader = 0;
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
                readfile(message);
                break;
            
            case WRQ:
                writefileCheck(message);
                break;

            // this case only catches the wrtie file data
            case DATA:
                writefile(message);
                break;    
            
            // this gets called from reading and get dir so i need to add some sort of salution for this 
            case ACK:{
                conread(message);
                break;
            }

            case DIRQ:
                getFileNames();
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
            conns.send(connectionId,error((short)7));// sends back error
            return;
        }

        String name = new String(Arrays.copyOfRange(message, 2, message.length));
        /// checks if this name is unique 
        for (Map.Entry<Integer, String> entry : GlobalData.ids.entrySet()) {
            if (name.equals(entry.getValue())) {
                conns.send(connectionId, error((short) 7)); // name is not unique
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

    private void writefileCheck(byte[] msg){
        reader = 0;
        String fname = new String(Arrays.copyOfRange(msg, 2, msg.length));
        // max number that can be get with a short * 512
        fileData = new byte[16777344];
        fileToWrite = path + fname;
        File file = new File(fileToWrite);

        // Checks if the file already exists
        if (file.exists()){
            conns.send(connectionId, error((short)5));
            return;
        }

        conns.send(connectionId,sendAck((short)0));
    }

    private void writefile(byte[] message){

        File file = new File(fileToWrite);
        // checks again if someone created the file
        if (file.exists()){
            conns.send(connectionId, error((short)5));
            return;
        }
        // checks if the right block was received 
        byte[] blockNum = new byte[2];
        blockNum[0] = message[4];
        blockNum[1] = message[5];
        short messageNum = arrToShort(blockNum);
        if (reader != messageNum - 1){
            conns.send(connectionId, error((short) 0));
            return;
        }

        // gets the length of the data
        byte[] dataSize = new byte[2];
        dataSize[0] = message[2];
        dataSize[1] = message[3];
        short len = arrToShort(dataSize);

        // extracts the actual data
        byte[] toWrite = new byte[len]; 
        System.arraycopy(message, 6, toWrite, 0, message.length - 6);
        
        // write into the right place the new data
        System.arraycopy(toWrite, 0, fileData, reader*maxDataPack, len);
        int size = len + reader*maxDataPack;

        // sends back an ACK packet 
        reader++;
        conns.send(connectionId,sendAck(messageNum));

        // technically this might bug out but there is no way of telling if the file transfer has finished if the files size
        // happens to be exacly a multiplication of 512
        if (len != maxDataPack){
            // creates the file
            try{
                file.createNewFile();
                //writes the file
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(fileData, 0, size);
                } catch (IOException e) {
                    // need to check this 
                    if (e.getMessage() == "No space left on device")
                        conns.send(len, error((short) 3));
                    return;
                }
            }catch(IOException err){}

            //sends bcast
            GlobalData.ids.forEach((id,value) -> {
                if (value != "")
                    conns.send(id, bCastPack(true, fileToWrite.substring(path.length())));// sends the file name without the path
            });
        }
       
    }

    private void readfile(byte[] message){
        reader = 0;
        fileToWrite = path + new String(Arrays.copyOfRange(message, 2, message.length));
        try {
            File file = new File(fileToWrite);
            FileInputStream fis = new FileInputStream(file);

            // Get the length of the file
            long fileSize = file.length();

            // Create a byte array to store the file content
            fileData = new byte[(int) fileSize];

            // Read the file content into the byte array
            fis.read(fileData);

            // Close the FileInputStream
            fis.close();
        }
        catch(FileNotFoundException err){
            // file doesnt exist
            conns.send(connectionId, error((short) 1));
            return;
        }
        
        catch (IOException e) {
            e.printStackTrace();
        }
        conread(new byte[]{0x00,0x00,0x00,0x00});
    }

    private void conread(byte[] msg){
        long left = fileData.length - reader*maxDataPack;
        byte[] packData;
        //checks if right ack
        short ack =  ( short ) ((( short ) msg [2]) << 8 | ( short ) ( msg [3] & 0xff) );
        if(ack != reader)
            reader = ack;

        // everything has been read
        if(left < 0){
            reader++;
            conns.send(connectionId, dataPack(new byte[0], (short) reader));
            return;
        }

        if(left < maxDataPack)
            packData = Arrays.copyOfRange(fileData, reader*maxDataPack, fileData.length);
        else
            packData = Arrays.copyOfRange(fileData, reader*maxDataPack,reader*maxDataPack + maxDataPack);

        reader++;
        conns.send(connectionId, dataPack(packData, (short)reader));

    }

    private void getFileNames(){
        reader = 0;
        // creates a File object representing the directory
        File directory = new File(path);

        // gets all the file names in the directory
        String[] files = directory.list();

        // max number that can be get with a short * 512
        fileData = new byte[16777344];
        int indx = 0;
        
        // puts all the names in the array
        for (String name : files){
            for (byte b : name.getBytes()){
                fileData[indx] = b;
                indx++;
            }
            // adds delimiter
            fileData[indx] = '\0';
            indx++;
        }
        indx--;
        // resizes the array
        fileData = Arrays.copyOf(fileData, indx);
        conread(new byte[]{0x00,0x00,0x00,0x00});
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
        else
            conns.send(connectionId, error((short)6));
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
