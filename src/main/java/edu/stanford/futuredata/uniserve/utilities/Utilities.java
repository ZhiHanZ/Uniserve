package edu.stanford.futuredata.uniserve.utilities;

import com.google.protobuf.ByteString;
import edu.stanford.futuredata.uniserve.ReadQueryResponse;
import edu.stanford.futuredata.uniserve.interfaces.QueryPlan;
import org.javatuples.Pair;

import java.io.*;

public class Utilities {
    public static Pair<String, Integer> parseConnectString(String connectString) {
        String[] hostPort = connectString.split(":");
        String host = hostPort[0];
        Integer port = Integer.parseInt(hostPort[1]);
        return new Pair<>(host, port);
    }

    public static ByteString objectToByteString(Serializable obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out;
        out = new ObjectOutputStream(bos);
        out.writeObject(obj);
        out.flush();
        return ByteString.copyFrom(bos.toByteArray());
    }

    public static Object byteStringToObject(ByteString b) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(b.toByteArray());
        ObjectInput in = new ObjectInputStream(bis);
        Object obj = in.readObject();
        in.close();
        return obj;
    }
}