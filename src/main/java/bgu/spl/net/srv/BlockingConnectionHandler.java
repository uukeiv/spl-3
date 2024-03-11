package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.BidiMessagingProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final BidiMessagingProtocol<T> protocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Socket sock;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean connected = true;
    private BlockingQueue<byte[]> pq;  

    public BlockingConnectionHandler(Socket sock, MessageEncoderDecoder<T> reader, BidiMessagingProtocol<T> protocol) {
        this.sock = sock;
        this.encdec = reader;
        this.protocol = protocol;
        pq = new LinkedBlockingQueue<byte[]>();
    }

    @Override
    public void run() {
        try (Socket sock = this.sock) { //just for automatic closing
            int read;

            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());

            while (!protocol.shouldTerminate() && connected && (read = in.read()) >= 0) {
                T nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null)   
                    protocol.process(nextMessage);
                while(!pq.isEmpty())
                    sockSend();
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    @Override
    public void close() throws IOException {
        connected = false;
        sock.close();
    }

    public Socket getSocket(){
        return this.sock;
    }

    @Override
    public void startProtocol(int id, Connections<T> conns)
    {
        protocol.start(id, conns);
    }

    private void sockSend(){  
        System.out.println("sending a message " + Arrays.toString(pq.peek()));
        try{
            out.write(pq.poll());
            out.flush();
        }
        catch(IOException e){}
    }

    @Override
    public void send(T msg) {
        //IMPLEMENT IF NEEDED
        pq.add(encdec.encode(msg));   
    }

}
