package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.BidiMessagingProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
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
    private Connections<T> conns;
    private int connectionId;

    public BlockingConnectionHandler(Socket sock, MessageEncoderDecoder<T> reader, BidiMessagingProtocol<T> protocol, Connections<T> conns, int connectionId) {
        this.sock = sock;
        this.encdec = reader;
        this.protocol = protocol;
        this.conns = conns;
        this.connectionId = connectionId;
        pq = new LinkedBlockingQueue<byte[]>();
    }

    @Override
    public void run() {
        try (Socket sock = this.sock) { //just for automatic closing
            int read;
            protocol.start(connectionId, conns);

            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());
            while (!protocol.shouldTerminate() && connected ){
                T nextMessage = null;
                if (in.available() > 0){
                    if ((read = in.read()) >= 0)
                        nextMessage = encdec.decodeNextByte((byte) read);
                }
                
                if (nextMessage != null)   
                    protocol.process(nextMessage);
                while(!pq.isEmpty())
                    sockSend();
            }

        } catch (IOException ex) {}

    }

    @Override
    public void close() throws IOException {
        connected = false;
        //sock.close();
    }

    public Socket getSocket(){
        return this.sock;
    }

    private void sockSend(){  
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
