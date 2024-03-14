package bgu.spl.net.srv;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class connect<T> implements Connections<T>{
    
    private ConcurrentHashMap <Integer,ConnectionHandler<T>> handlers = new ConcurrentHashMap<Integer,ConnectionHandler<T>>();


    public void connect(int connectionId, ConnectionHandler<T> handler){
        GlobalData.ids.put(connectionId,"");
        handlers.put(connectionId, handler);
        Thread client = new Thread((Runnable) handler);
        client.start();
    }


    public boolean send(int connectionId, T msg){
        handlers.get(connectionId).send(msg);
        return true;
    }


    public void disconnect(int connectionId){
        GlobalData.ids.remove(connectionId);
        try {
            handlers.remove(connectionId).close();

        } catch (IOException e) {}
    }

}
