
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Pattern;
import java.util.regex.Matcher;


//
// This is an implementation of a simplified version of a command
// line ftp client.
//


public class CSftp {
    static final int MAX_LEN = 255;
    static final int ARG_CNT = 2;
    private static Socket socket;
    private static InputStream Istream;
    private static OutputStream Ostream;
    private static BufferedReader reader;
    private static BufferedWriter writer;
    static String wd; // working directory on the server
    static boolean loggedIn = false;
    static String hostname;
    static int port;


    public static void main(String[] args) {
        byte cmdString[] = new byte[MAX_LEN];


        // Get command line arguments and connected to FTP
        // If the arguments are invalid or there aren't enough of them
        // then exit.

        if (args.length < 1 || args.length > ARG_CNT) {
            System.out.print("Usage: cmd ServerAddress ServerPort\n");
            return;
        }

        hostname = args[0];

        if (args.length == 1) {
            port = 21;
        } else {
            port = Integer.parseInt(args[1]);
        }

        try {
            connect(hostname, port);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            for (int len = 1; len > 0; ) {
                System.out.print("csftp> ");

                cmdString = new byte[MAX_LEN];

                len = System.in.read(cmdString);
                if (len <= 0)
                    break;

                // Start processing the command here.

                String userInput = new String(cmdString);
                String trimmed = userInput.trim();
                String[] commands = trimmed.split("\\s+");
                int argNum = commands.length;

                String command = commands[0].toLowerCase();
                String command2 = "";

                if (argNum == 2) {
                    command2 = commands[1];
                }

                if (command.equals("user")) {
                    if (argNum != 2) {
                        System.out.println(getErrorMsg("0x002"));
                    } else if (!isConnected()) {
                        System.out.println(getErrorMsg("0xFFFC", hostname, port));
                        System.exit(0);
                    } else {
                        sendUser(command2);
                    }
                    
                } else if (command.equals("pw")) {
                    if (argNum != 2) {
                        System.out.println(getErrorMsg("0x002"));
                    } else {
                    sendPassword(command2);
                    }
                    
                } else if (command.equals("cd")) {
                    if (argNum != 2) {
                        System.out.println(getErrorMsg("0x002"));
                    }
                    cd(command2);

                } else if (command.equals("quit")) {
                    if (argNum != 1) {
                        System.out.println(getErrorMsg("0x002"));
                    } else {
                        quit();
                        return;
                    }

                } else if (command.equals("get")) {
                    if (argNum != 2) {
                        System.out.println(getErrorMsg("0x002"));
                    } else {
                    get(command2);
                    }
                    
                } else if (command.equals("features")) {
                    if (argNum != 1) {
                        System.out.println(getErrorMsg("0x002"));
                    } else {
                        features();
                    }

                } else if (command.equals("dir")) {
                    if (argNum != 1) {
                        System.out.println(getErrorMsg("0x002"));
                    } else {
                        dir();
                    }

                } else {
                    System.out.println(getErrorMsg("0x001"));
                }
            }

        } catch (IOException exception) {
            System.out.println(getErrorMsg("0xFFFE"));
        }
    }

    /**
     * Sends the given username to the FTP server.
     *
     * @param {String} user - The username to be sent to the FTP server.
     */

    public static void sendUser(String user) throws IOException {
        writeToServer("USER " + user);
        String response = readFromServer();

        // 230: Client has permission to access files under username
        // 331 or 332: Permission might be granted after PASS request
        if (!response.contains("230") && !response.contains("220") && !response.contains("331") && !response.contains("332")) {
            System.out.println(getErrorMsg("0xFFFF", response));
        }
    }


    /**
     * Sends the given password to the FTP server.
     *
     * @param {String} password - The password to be sent to the FTP server.
     */

    public static void sendPassword(String password) throws IOException {
        writeToServer("PASS " + password);
        String response = readFromServer();

        if (response.contains("230") || response.contains("202")) {
            loggedIn = true;
        }

        // 503: Reject pw b/c previous request wasn't USER
        // 530: Username and password are jointly unacceptable
        if (response.contains("503") || response.contains("530")) {
            System.out.println(getErrorMsg("0xFFFF", response));
        }
    }

    /**
     * Changes the current working directory on the server to the directory given.
     *
     * @param {String} directory - The new directory.
     */

    public static void cd(String directory) throws IOException {
        writeToServer("CWD " + directory);
        String response = readFromServer();

        // Request accepted
        if (response.contains("257") || response.contains("200") || response.contains("250")) {
            wd = directory;
        }

        // 550: No such file or directory
        if (response.contains("550")) {
            System.out.println(getErrorMsg("0xFFFF", response));
        }
    }

    /**
     * Requests the set of features/extensions the server supports.
     */

    public static void features() throws IOException {
        writeToServer("FEAT");
        String response = readFromServer();

        if (response.contains("500") || response.contains("501")) {
            System.out.println(getErrorMsg("0xFFFF", response));
        }
    }

    /**
     * If connected, closes any established connection and then exits the program.
     */

    public static void quit() throws IOException {
        if (isConnected()) {
            writeToServer("QUIT");
            readFromServer();
            reader.close();
            writer.close();
            System.exit(0);
        } else {
            System.out.println("0x001");
        }
    }

    /**
     * Establishes a data connection and retrieves the file given.
     * Saves the file in another file of the same name on the local machine.
     *
     * @param {String} remote - The file name to be retrieved.
     */

    public static void get(String remote) throws IOException {
        writeToServer("PASV");
        String pasvResp = readFromServer();

        if (pasvResp.contains("227")) {
            String respNumber = pasvResp.substring(pasvResp.indexOf("(") + 1, pasvResp.indexOf(")"));
            String[] addrParts = respNumber.split(",");
            String IPaddress = getIPaddress(addrParts);
            int portNumber = getPortNumber(addrParts);
            Socket passiveSocket = passiveConnect(IPaddress, portNumber);

            writeToServer("RETR " + remote);

            String retrResp = readFromServer();


            if (retrResp.contains("150")) {
                InputStream inputStream = passiveSocket.getInputStream();
                OutputStream outputStream = passiveSocket.getOutputStream();
                int fileSize = getFileSize(retrResp);
                File file = new File(remote);

                try {
                    byte[] byteArray = new byte[fileSize];
                    FileOutputStream fop = new FileOutputStream(file);
                    int fileLength = -1;
                    while ((fileLength = inputStream.read(byteArray)) != -1) {
                        fop.write(byteArray, 0, fileLength);
                    }
                } catch (IOException e) {
                    System.out.println(getErrorMsg("0x3A7"));
                }

                retrResp = readFromServer();
                inputStream.close();
                outputStream.close();
                passiveSocket.close();

            } else if (retrResp.contains("451")) {
                System.out.println(getErrorMsg("0x38E", remote));
            } else if (retrResp.contains("550")) {
                System.out.println(getErrorMsg("0xFFFF", retrResp));
            } else if (retrResp.contains("426")) {
                System.out.println(getErrorMsg("0x3A7"));
            }
        }
    }

    /**
     * Returns the byte file size of the file to be saved to disk by
     * parsing the response code.
     *
     * @param {String} resp - The response line that contains the file size.
     * @return {int}
     */

    public static int getFileSize(String resp) {
        int fileSize = -1;

        int start = resp.indexOf("(");
        int end = resp.indexOf(")");

        String respSubstring = resp.substring(start, end + 1);

        Pattern pattern = Pattern.compile("-?\\d+");
        Matcher matcher = pattern.matcher(respSubstring);
        boolean matchFound = matcher.find();

        if (matchFound) {
            fileSize = Integer.parseInt(matcher.group(0));
        }
        return fileSize;
    }

    /**
     * Establishes a data connection and retrieves a list of files in the
     * current working directory on the server.
     */

    public static void dir() throws IOException {
        writeToServer("PASV");
        String listResp = readFromServer();

        if (listResp.contains("227")) {
            String respNumber = listResp.substring(listResp.indexOf("(") + 1, listResp.indexOf(")"));
            String[] addrParts = respNumber.split(",");
            String IPaddress = getIPaddress(addrParts);
            int portNumber = getPortNumber(addrParts);
            Socket passiveSocket = passiveConnect(IPaddress, portNumber);

            writeToServer("LIST ");
            listResp = readFromServer();

            if (listResp.contains("150") || listResp.contains("125")) {
                InputStream iStream = passiveSocket.getInputStream();
                BufferedReader passiveReader = new BufferedReader(new InputStreamReader(iStream));
                String list = passiveReader.readLine();
                while (list != null) {
                    System.out.println(list);
                    list = passiveReader.readLine();
                }
                passiveReader.close();
                passiveSocket.close();
                listResp = readFromServer();
                // 226: Entire directory was successfully transmitted
                if (listResp.contains("226")) {
                    // 425: No TCP connection was established
                    // 426: TCP connection was established but then broken
                    // 451: Server had trouble reading directory from disk
                } else if (listResp.contains("425") || listResp.contains("426") || listResp.contains("451")) {
                    System.out.println(getErrorMsg("0x3A7"));

                }

            }
        }
    }

    /**
     * Returns the IP address.
     *
     * @param {String} addrParts
     * @return {String}
     */

    public static String getIPaddress(String[] addrParts) {
        return addrParts[0] + "." + addrParts[1] + "." + addrParts[2] + "." + addrParts[3];
    }

    /**
     * Returns the port number.
     *
     * @param {String} addrParts
     * @return {int}
     */

    public static int getPortNumber(String[] addrParts) {
        return Integer.parseInt(addrParts[4]) * 256 + Integer.parseInt(addrParts[5]);
    }

    /**
     * Returns the error message corresponding to the given hex code.
     *
     * @param {String} code - The error message's hex code.
     * @return {String}
     */

    private static String getErrorMsg(String code) {
        if (code == "0x001") {
            return "0x001 Invalid command.";
        } else if (code == "0x002") {
            return "0x002 Incorrect number of arguments.";
        } else if (code == "0xFFFD") {
            return "0xFFFD Control connection I/O error, closing control connection.";
        } else if (code == "0x3A7") {
            return "0x3A7 Data transfer connection I/O error, closing data connection.";
        } else if (code == "0xFFFE") {
            return "0xFFFE Input error while reading commands, terminating.";
        } else {
            return "";
        }
    }

    /**
     * Returns the error message corresponding to the given hex code.
     *
     * @param {String} code - The error message's hex code.
     * @param {String} msg1 - The message to be included in the error message.
     * @return {String}
     */

    private static String getErrorMsg(String code, String msg1) {
        if (code == "0x38E") {
            return "0x38E Access to local file " + msg1 + " denied.";
        } else if (code == "0xFFFF") {
            return "0xFFFF Processing error. " + msg1;
        } else {
            return "";
        }
    }

    /**
     * Returns the error message corresponding to the given hex code.
     *
     * @param {String} code - The error message's hex code.
     * @param {String} address
     * @param {String} port
     * @return {String}
     */

    private static String getErrorMsg(String code, String address, int port) {
        if (code == "0xFFFC") {
            return "0xFFFC Control connection to " + address + " on port " + port + " failed to open.";
        } else if (code == "0x3A2") {
            return "0x3A2 Data transfer connection to " + address + " on port " + port + " failed to open.";
        } else {
            return "";
        }
    }

    /**
     * Returns true if the socket is connected, else false.
     *
     * @return {Boolean}
     */

    public static boolean isConnected() {
        return socket.isConnected();
    }

    /**
     * Connects to the given hostname and port.
     *
     * @param {String} host
     * @param {int}    port
     */

    public synchronized static void connect(String host, int port) throws IOException {
        int timeout = 20000;

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeout);
            Istream = socket.getInputStream();
            Ostream = socket.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(Istream));
            writer = new BufferedWriter(new OutputStreamWriter(Ostream));
            readFromServer();
        } catch (IOException e) {
            System.out.println(getErrorMsg("0xFFFC", host, port));
            socket.close();
            System.exit(0);

        }
    }

    /**
     * Prints and returns the message received by the client program on the console.
     *
     * @return {String}
     */

    public static String readFromServer() throws IOException {
        String resp = "";
        Boolean finalLine = false;

        try {
            while (finalLine == false) {
                resp = reader.readLine();
                System.out.println("<-- " + resp);
                finalLine = isFinalLine(resp);
            }
        } catch (IOException e) {
            System.out.println(getErrorMsg("0xFFFD"));
            socket.close();
            System.exit(0);
        }
        return resp;
    }

    /**
     * Returns true if the given input is an integer, else false.
     *
     * @param {String} s
     * @return {Boolean}
     */

    public static boolean isInteger(String s) {
        boolean isInteger = false;

        try {
            Integer.parseInt(s);
            isInteger = true;
        } catch (NumberFormatException e) {
            // do nothing
        }
        return isInteger;
    }

    /**
     * Returns true if the given response line is the last line, else false.
     * The last line begins in the form: <response code><space>.
     *
     * @param {String} resp - A response line.
     * @return {Boolean}
     */

    public static Boolean isFinalLine(String resp) {
        // checks for an empty line
        if (resp.length() == 1) {
            return false;
        }

        String respCode = resp.substring(0, 3).trim();
        String index3 = resp.substring(3, 4);

        if (respCode.length() > 0 && index3.length() > 0) {
            if (isInteger(respCode) && index3.equals(" ")) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Prints the message sent by the client to the FTP server on the console.
     *
     * @return {Boolean}
     */

    public static Boolean writeToServer(String cmd) throws IOException {
        System.out.println("--> " + cmd + "\r\n");
        try {
            writer.write(cmd + "\r\n");
            writer.flush();
        } catch (IOException e) {
            System.out.println(getErrorMsg("0xFFFD"));
            socket.close();
            System.exit(0);
        }
        return socket.isConnected();
    }

    /**
     * Connects to the given hostname and port. Creates a passive connection.
     *
     * @param {String} host
     * @param {int}    port
     */

    public static Socket passiveConnect(String host, int port) throws IOException {
        Socket passiveSocket = null;
        int timeout = 10000;

        try {
            passiveSocket = new Socket();
            passiveSocket.connect(new InetSocketAddress(host, port), timeout);
        } catch (IOException e) {
            System.out.println(getErrorMsg("0x3A2", host, port));
            passiveSocket.close();
        }
        return passiveSocket;
    }
}