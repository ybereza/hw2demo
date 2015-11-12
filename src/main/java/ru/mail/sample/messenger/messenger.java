package ru.mail.sample.messenger;

import com.google.gson.*;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Scanner;

public class messenger {

    public static final String HOST = "188.166.49.215";
    public static final int PORT = 7777;

    private static class MessageReceiver implements Runnable {
        private final Socket mSocket;
        private final InputStream mStream;

        private interface Message {
            void parse(JsonObject json);
            void doOutput();
        }

        private static class Welcome implements Message {
            private String message;
            private long time;

            @Override
            public void parse(JsonObject json) {
                message = json.get("message").getAsString();
                time = json.get("time").getAsLong();
            }

            @Override
            public void doOutput() {
                Date serverTime = new Date(time);
                System.out.println(message);
                System.out.println("Server time is: "+serverTime);
                System.out.println();
                System.out.println("Enter one of commands: exit, login, register");
            }
        }

        private static class Registration implements Message {
            private int status;
            private String error;

            @Override
            public void parse(JsonObject json) {
                JsonObject data = json.get("data").getAsJsonObject();
                status = data.get("status").getAsInt();
                error = data.get("error").getAsString();
            }

            @Override
            public void doOutput() {
                if (status == 0) {
                    System.out.println("Registration successful!");
                }
                else {
                    System.out.println("Error! " + error);
                }
            }
        }

        private static class Auth implements Message {
            private int status;
            private String error;

            @Override
            public void parse(JsonObject json) {
                JsonObject data = json.get("data").getAsJsonObject();
                status = data.get("status").getAsInt();
                error = data.get("error").getAsString();
            }

            @Override
            public void doOutput() {
                if (status == 0) {
                    System.out.println("Authorization successful!");
                }
                else {
                    System.out.println("Error! " + error);
                }
            }
        }

        public HashMap<String, Message> mMessages = new HashMap<>();

        public MessageReceiver(Socket communicationSocket) throws IOException {
            mSocket = communicationSocket;
            mStream = new BufferedInputStream(communicationSocket.getInputStream());

            mMessages.put("welcome", new Welcome());
            mMessages.put("register", new Registration());
            mMessages.put("auth", new Auth());
        }

        @Override
        public void run() {
            try {
                boolean stop = false;
                boolean cleanup = false;
                byte[] data = new byte[32768];
                int offset = 0;
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                JsonParser parser = new JsonParser();

                do {
                    if (cleanup) {
                        outputStream.reset();
                        offset = 0;
                        cleanup = false;
                    }
                    int readBytes = mStream.read(data);
                    if (readBytes != -1) {
                        outputStream.write(data, offset, readBytes);
                        offset += readBytes;
                        outputStream.flush();
                        String result = outputStream.toString("utf-8");
                        if (result.endsWith("}")) {
                            try {
                                JsonElement element = parser.parse(result);
                                JsonObject json = element.getAsJsonObject();
                                JsonElement action = json.get("action");
                                if (action != null) {
                                    Message message = mMessages.get(action.getAsString());
                                    if (message != null) {
                                        message.parse(json);
                                        message.doOutput();
                                        cleanup = true;
                                    }
                                }
                            }
                            catch (JsonSyntaxException e) {
                                //not full json, continue
                            }
                        }
                    }
                    else {
                        stop = true;
                    }
                } while (!stop);
                mStream.close();
                mSocket.close();
                System.out.println("Connection closed from server side. Good bye!");
            }
            catch (SocketException e) {
                //connection is closed;
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class MessageSender implements Runnable {
        private final Socket mSocket;
        private final OutputStream mStream;

        private interface Action {
            boolean proceedInput(String input);
            String getAction();
        }

        private static class Registration implements Action {
            private enum Stage {
                START,
                LOGIN,
                PASSWORD,
                NICK
            }
            Stage mStage = Stage.START;

            private String mLogin;
            private String mPass;
            private String mNick;

            @Override
            public boolean proceedInput(String input) {
                boolean done = false;
                switch (mStage) {
                    case START:
                        System.out.print("Please enter login: ");
                        mStage = Stage.LOGIN;
                        break;
                    case LOGIN:
                        mLogin = input;
                        System.out.print("Please enter password: ");
                        mStage = Stage.PASSWORD;
                        break;
                    case PASSWORD:
                        mPass = input;
                        System.out.print("Please enter nick name: ");
                        mStage = Stage.NICK;
                        break;
                    case NICK:
                        mNick = input;
                        System.out.println("Trying to register...");
                        done = true;
                        break;
                }
                return done;
            }

            @Override
            public String getAction() {
                if (mLogin != null && mPass != null && mNick != null) {
                    JsonObject action = new JsonObject();
                    action.addProperty("action", "register");
                    JsonObject data = new JsonObject();
                    data.addProperty("login", mLogin);
                    data.addProperty("pass", md5(mPass));
                    data.addProperty("nick", mNick);
                    action.add("data", data);
                    return action.toString();
                }
                return null;
            }
        }

        private static class Auth implements Action {
            private enum Stage {
                START,
                LOGIN,
                PASSWORD
            }
            Stage mStage = Stage.START;

            private String mLogin;
            private String mPass;

            @Override
            public boolean proceedInput(String input) {
                boolean done = false;
                switch (mStage) {
                    case START:
                        System.out.print("Please enter login: ");
                        mStage = Stage.LOGIN;
                        break;
                    case LOGIN:
                        mLogin = input;
                        System.out.print("Please enter password: ");
                        mStage = Stage.PASSWORD;
                        break;
                    case PASSWORD:
                        mPass = input;
                        System.out.println("Trying to authorize...");
                        done = true;
                        break;
                }
                return done;
            }

            @Override
            public String getAction() {
                if (mLogin != null && mPass != null) {
                    JsonObject action = new JsonObject();
                    action.addProperty("action", "auth");
                    JsonObject data = new JsonObject();
                    data.addProperty("login", mLogin);
                    data.addProperty("pass", md5(mPass));
                    action.add("data", data);
                    return action.toString();
                }
                return null;
            }
        }

        private HashMap<String, Action> mActions = new HashMap<>();

        public MessageSender(Socket communicationSocket) throws IOException {
            mSocket = communicationSocket;
            mStream = new BufferedOutputStream(communicationSocket.getOutputStream());
            mActions.put("login", new Auth());
            mActions.put("register", new Registration());
        }

        @Override
        public void run() {
            Scanner scanner = new Scanner(System.in);
            boolean stop = false;
            Action currentAction = null;
            while (!stop && scanner.hasNext()) {
                String line = scanner.nextLine();
                if (line.equalsIgnoreCase("exit") && currentAction == null) {
                    stop = true;
                }
                else {
                    if (currentAction == null) {
                        currentAction = mActions.get(line);
                    }
                    if (currentAction != null) {
                        if (currentAction.proceedInput(line)) {
                            String actionJson = currentAction.getAction();
                            byte[] data = actionJson.getBytes(Charset.forName("UTF-8"));
                            try {
                                mStream.write(data);
                                mStream.flush();
                                currentAction = null;
                            } catch (IOException e) {
                                //maybe connection lost
                                stop = true;
                            }
                        }
                    }
                    else {
                        stop = true;
                    }
                }
            }
            try {
                mStream.close();
                mSocket.close();
                System.out.println("Connection is closed");
            }
            catch (IOException e) {
                System.out.println("Can not close socket. Application aborted");
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("Welcome to messenger! Connecting to server...");

        try {
            Socket socket = new Socket(HOST, PORT);
            Thread sender = new Thread(new MessageSender(socket));
            Thread receiver = new Thread(new MessageReceiver(socket));
            receiver.start();
            sender.start();

            receiver.join();
            sender.join();
        }

        catch (IOException e) {
            System.out.println("Connection aborted due to exception " + e.getMessage() );
        }

        catch (InterruptedException e) {
            e.printStackTrace();
            System.out.println("Abnormal interruption. Good bye");
        }
    }

    public static String md5(String s) {
        String md5sum = null;
        try {
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte messageDigest[] = digest.digest();

            StringBuffer hexString = new StringBuffer();
            for (int i=0; i<messageDigest.length; i++)
                hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
            md5sum = hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return md5sum;
    }

}
