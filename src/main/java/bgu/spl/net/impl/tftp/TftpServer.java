package bgu.spl.net.impl.tftp;
import bgu.spl.net.impl.echo.EchoProtocol;
import bgu.spl.net.impl.echo.LineMessageEncoderDecoder;
import bgu.spl.net.srv.Server;



public class TftpServer{

    static final int portArg = 0;
    //TODO: Implement this
    public static void main(String[] args) {
        int port = Integer.parseInt(args[portArg]);
        // you can use any server... 
        Server.threadPerClient(
                port, //port
                () -> new TftpProtocol(), //protocol factory
                TftpEncoderDecoder::new //message encoder decoder factory
        ).serve();

    }

}
