import java.net.*;
import java.io.*;
import java.util.Scanner;
import java.util.concurrent.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Calendar;
import java.nio.file.Files;



// the main class that controls the server. 
//after doing some initial strtup stuff like taking command-line input,
// the while loop listens for clients and spawns threads to handle any.
public class HTTP1Server {
	public static void main(String[] args) throws IOException {

		if (args.length < 1){
			System.out.println("Program requires a port number as command line input.");
			return;
		}
		int port;
		try{
			port = Integer.parseInt(args[0]);
		}
		catch(NumberFormatException nfe){
			System.out.println("Port number must be a non-negative integer less than 65536.");
			return;
		}
		ServerSocket ss = null;
		try{
			ss = new ServerSocket(port);
		}
		catch(BindException be){
			System.out.println("That port is already in use. Try a different port.");
			return;
		}
		catch(IOException ioe){
			System.out.println("Something went wrong when trying to construct your server socket. Try again.");
		}

		Socket client = null;

		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		executor.setCorePoolSize(5);
		executor.setMaximumPoolSize(50);

		//main server waiting loop. get a client, send it to a handler and keep listening.
		while((client = ss.accept()) != null){
 
			if(executor.getActiveCount() > 49){
				PrintWriter pw = new PrintWriter(client.getOutputStream(), true);
				pw.print("HTTP/1.0 503 Service Unavailable" + '\r' + '\n');
				pw.flush();
				pw.close();
				client.close();
			}

			else{
				executor.execute(new HandlerThread(client));
			}
		}
		ss.close();
	}
}

//The class that handles clients.
//When the server finds a new client, it sends them here to be handled.
//The client's request is parsed, and a response is sent back depending on the contents of the request.
//This response will contain:
	//either an error message if the client made a mistake, or if the server was unable to handle the request
	//or, a success message and the requested content.
class HandlerThread extends Thread{

	//all client handling threads will have these variables. 
	//their values are based on the client and are initialized in the constructor.
	Socket client = null;
	BufferedReader br = null;
	PrintWriter pw = null;

	//constructor. takes the client socket, forms input and output based on it.
	public HandlerThread(Socket s) throws IOException{
		client = s;
		br = new BufferedReader(new InputStreamReader(client.getInputStream()));	
		pw = new PrintWriter(client.getOutputStream(), true);
	}

	//this is a handy method for ending client communications. pretty straightforward.
	//it sleeps because that was in the project requirements.
	public void shutdown() throws IOException{
		try{
			pw.flush();
			Thread.sleep(250);
			br.close();
			pw.close();
			client.close();
		}
		catch(InterruptedException ie){
			return;
			// ... idk what to do if that happens
		}
	}


	//checks for properly formatted HTTP request.
	//splits request line by line and checks each in its own method.
	public boolean correctFormat(String req){

		//checks for empty string. Cant have Exceptions later.
		if (req.length() == 0) return false;

		String [] lines = req.split("\r\n");

		String mainLine = lines[0];

		if (mainLine.length() == 0) return false;
		
		//checks for leading or trailing spaces. not sure this is necessary but it doesnt hurt
		if (mainLine.charAt(0) == ' ' || mainLine.charAt(mainLine.length()-1) == ' ') return false;

		String [] tokens = mainLine.split(" ");
		//there should be exactly THREE tokens. command(all caps), resource(filename prefixed with a slash), and version(i.e. "HTTP/#.#")

		if(tokens.length != 3) return false;

		String command = tokens[0];
		String resource = tokens[1];
		String version = tokens[2];

		if(!command.equals(command.toUpperCase())) return false; //command needs to be in caps
		if(!( 
				command.equals("GET") || 
				command.equals("POST") || 
				command.equals("HEAD") || 
				command.equals("DELETE") || 
				command.equals("PUT") || 
				command.equals("LINK") || 
				command.equals("UNLINK")
			)) return false;

		if(resource.charAt(0) != '/') return false;

		String [] versionInfo = version.split("/"); //WHAT IF THERE IS NO SLASH?

		if (!versionInfo[0].equals("HTTP")) return false;

		try{
			float versionNumber = Float.parseFloat(versionInfo[1]);
		}
		catch(NumberFormatException nfe){
			return false;
		}
		
		return true;
	
	}

	//for a given number of millseconds from since the epoch,
	//this method turns that number into an HTTP-readable version.
	public String getFormattedTime(long millis){
		
		DateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(millis);
		
		String s = formatter.format(calendar.getTime());

		return s; 

	}



	//this method checks the given file's length, file type, and any encoding.
	//then, it creates appropriate HTTP response headers and returns them.
	public String addHeaders(File fileToRead){

		String name = fileToRead.getName();
		String extension = name.substring(name.lastIndexOf('.') + 1);
		String typeLine = "Content-Type: ";
		String mime;
		if(extension.equals("txt") || extension.equals("html")) mime = "text";
		else if(extension.equals("png") || extension.equals("jpeg") || extension.equals("gif")) mime = "image";
		else if(extension.equals("pdf") || extension.equals("x-gzip") || extension.equals("zip") || extension.equals("octet-stream")) mime = "application";
		else{
			mime = "application";//default values
			extension = "octet-stream";
		}
		typeLine += mime + "/" + extension  + '\r' + '\n';

		long length = fileToRead.length();
		String lengthLine = "Content-Length: "+length + '\r' + '\n';

		long time = fileToRead.lastModified();
		String modifiedLine = "Last-Modified: " + getFormattedTime(time)  + '\r' + '\n';

		String encodingLine = "Content-Encoding: identity"  + '\r' + '\n';

		String allowLine = "Allow: GET, POST, HEAD"  + '\r' + '\n';

		String expireLine = "Expires: " + getFormattedTime(System.currentTimeMillis() + 525600) + '\r' + '\n'; //picked a random number. Thank you Rent

		return typeLine + lengthLine + modifiedLine + encodingLine + allowLine + expireLine;


	}


	//the same method as above, but overloaded. This one works with POST requests.
	//differences: content type isalways text/html, and content length is always the length of the output payload (which is the paramter)
	public String addHeaders(File fileToRead, int CL){

		String typeLine = "Content-Type: ";
		typeLine += "text/html"  + '\r' + '\n';

		String lengthLine = "Content-Length: "+ CL + '\r' + '\n';

		long time = fileToRead.lastModified();
		String modifiedLine = "Last-Modified: " + getFormattedTime(time)  + '\r' + '\n';

		String encodingLine = "Content-Encoding: identity"  + '\r' + '\n';

		String allowLine = "Allow: GET, POST, HEAD"  + '\r' + '\n';

		String expireLine = "Expires: " + getFormattedTime(System.currentTimeMillis() + 525600) + '\r' + '\n'; //picked a random number. Thank you Rent


		return typeLine + lengthLine + modifiedLine + encodingLine + allowLine + expireLine;


	}


	//this method checks HTTP requests to see if they have an if-modified-since clause.
	//if they do, it returns the specified time, as millis from epoch.
	//if they dont, it returns -1.
	//the request is assumed to be properly formatted.
	public long getIfModifiedTime(String request){

		String [] lines = request.split("\r\n");

		for(String i: lines){
			String field = i.split(" ")[0];
			if(field.equals("If-Modified-Since:")){
				String timeString = i.substring(19);
				SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
				try{
					return format.parse(timeString).getTime();
				}
				catch(ParseException pe){ //this will trigger if the time wasn't correctly formatted
					return -1;
				}
			}
		}
		return -1;

	}


	//Checks request headers for content-type, and returns it.
	//This applies for POST requests only, as of Project Part 2.
	public String getContentType(String request){

		String [] lines = request.split("\r\n");

		for(String i: lines){
			String field = i.split(" ")[0];
			if(field.equals("Content-Type:")){
				String typeString = i.substring(14);
				return typeString;
			}
		}
		return null; //IF THE CONTENT TYPE IS NOT PRESENT
		//this should result in 500

	}

	//Checks request headers for content-length, gets it as an integer, and returns it.
	//returns as an Integer because it...
	//returns null if no content length is found, or if it is non-integer.
	public int getContentLength(String request){

		String [] lines = request.split("\r\n");

		for(String i: lines){
			String field = i.split(" ")[0];
			if(field.equals("Content-Length:")){
				String lengthString = i.substring(16);
				int retval;
				try{
					retval = Integer.parseInt(lengthString);
				}
				catch(NumberFormatException nfe){
					retval = -1; // becomes 411
				}
				return retval;
			}
		}

		return -1; //becomes 411
	}

	//returns value of http post header field "From".
	//returns null if not found.
	public String getFrom(String request){
		String [] lines = request.split("\r\n");
		for(String i: lines){
			String field = i.split(" ")[0];
			if(field.equals("From:")){
				return i.substring(6);
			}
		}
		return null; //wasnt found.
	}

	//returns value of http post header field "User Agent".
	//returns null if not found.
	public String getUserAgent(String request){
		String [] lines = request.split("\r\n");
		for(String i: lines){
			String field = i.split(" ")[0];
			if(field.equals("User-Agent:")){
				return i.substring(12);
			}
		}
		return null; //wasnt found.
	}

	//parse request, extract and return payload as is.
	//im assuming the payload is all on one line??!?!?!
	public String getPayload(String request){
		
		String [] lines = request.split("\r\n");

		for(int i = 0; i < lines.length; i++){
			if(lines[i].equals("")){
				return lines[i+1]; //fingers crossed here... 
				//issues could arise upon bad formatting that the format checker doesnt check?
			}
		}

		return null; //if there was no payload, I guess
	}

	//self-explanatory. 
	//Given an input stream,
	//reads into a String until the end.
	//then, returns it.
	public String readInputStream(InputStream is) throws IOException{ // i say throws IOE because of br.ready and br.read. gets caught out in run method
		String retval = "";
		BufferedReader buff = new BufferedReader(new InputStreamReader(is));
		while(buff.ready()){
			retval += (char)buff.read();
		}
		return retval;
	}
	
	//for URL encoding. Given a string representing the HTTP code,
	//returns the corresponding char.
	public static char map(String code){
		switch(code){
			case "%21": return '!';
			case "2A": return '*'; case "%2a": return '*';
			case "%27": return '\'';
			case "%28": return '(';
			case "%29": return ')';
			case "%3b": return ';'; case "%3B": return ';';
			case "%3a": return ':'; case "%3A": return ':';
			case "%40": return '@'; 
			case "%26": return '&';
			case "%3d": return '='; case "%3D": return '=';
			case "%2b": return '+'; case "%2B": return '+'; 
			case "%24": return '$';
			case "%2c": return ','; case "%2C": return ',';
			case "%2f": return '/'; case "%2F": return '/';
			case "%3f": return '?'; case "%3F": return '?';
			case "%23": return '#';
			case "%5b": return '['; case "%5B": return '['; 
			case "%5d": return ']'; case "%5D": return ']';
			case "%20": return ' ';


		}
		return '1'; //error case. This means something went wrong.
	}


	//given a String,
	//this method alters each reserved HTTP encoded character to be its normal value.
	//then, it returns the altered String.
	public static String urlEncode(String s) throws IndexOutOfBoundsException{ 

		if(s == null) return null;

		for(int i = 0; i < s.length(); i++){

			if(s.charAt(i) == '%'){
				s = s.substring(0, i) + map(s.substring(i, i + 3)) + s.substring(i + 3);
			}

		}

		return s;

	}
			

	//this method is called when a Thread is started.
	//this is where all the parsing and evaluating happens.
	//if the client's request was properly formed,
	//File I/O will occur using a Scanner to get the specified data.
	public void run(){
		
		try{	
			
			//this chunk detects for null inputs and cancels the connection after 3 seconds if no input is given in that time.
			long start = System.currentTimeMillis();
			while(br.ready() == false){
				if(System.currentTimeMillis() - start > 3000){
					pw.print("HTTP/1.0 408 Request Timeout" + '\r' + '\n');
					shutdown();
					return;
				}
			}

			String request = "";

			while(br.ready()){
				int temp = br.read();
				request += (char)temp;
			}

			if(correctFormat(request) == false){
				pw.print("HTTP/1.0 400 Bad Request" + '\r' + '\n');
				shutdown();
				return;
			}
			
			//FROM HERE ON, THE REQUEST **SHOULD BE** ASSUMED TO BE PROPERLY FORMATTED (except for the headers that need to be done as of thurs 6:30)

			String [] tokens = request.split("\r\n")[0].split(" ");
			
			String command = tokens[0];
			String resource = tokens[1];
			String version = tokens[2];

			String [] versionInfo = version.split("/"); //WHAT IF THERE IS NO SLASH?
	
			float versionNumber = Float.parseFloat(versionInfo[1]);

			if(versionNumber > 1.0){
				pw.print("HTTP/1.0 505 HTTP Version Not Supported" + '\r' + '\n');
				shutdown();
				return;
			}		

			//check 2: command known?
			if(command.equals("HEAD") || command.equals("GET")){
				

				// i use substring here because java doesnt need the initial slash to find the resource...
				File fileToRead = new File("." + resource);//.substring(1)
				//this wont fail on file not found, it will happily proceed despite being empty

				if(!fileToRead.exists()){ 
					pw.print("HTTP/1.0 404 Not Found" + '\r' + '\n');
					shutdown();
					return;
				}

				if(fileToRead.isDirectory()){
					pw.print("HTTP/1.0 400 Bad Request" + '\r' + '\n'); // i guess so?
					shutdown();
					return;
				}
				
				long modifiedTime = fileToRead.lastModified();
				long ifModifiedTime = getIfModifiedTime(request);
				if(  (!command.equals("HEAD")) &&  ifModifiedTime != -1 && modifiedTime > ifModifiedTime){ //HTTP 1.0  rfc says HEAD cannot be conditional. see 8.2
					pw.print("HTTP/1.0 304 Not Modified" + '\r' + '\n' + "Expires: a future date" + '\r' + '\n');
					shutdown();
					return;
				}


				Scanner sc = null;
				
				try{

					//I use a scanner to read from a File
					sc = new Scanner(fileToRead);
				}

				// Okay, this is a bit confusing. 
				// Checking for 404 Not Found is already done by this point.
				// This catch is only catching FNF because that is also thrown for no read permissions!
				// Weirdly, File.canRead() doesnt work??? but this does????? whateverrr 
				catch(FileNotFoundException fnfe){
					pw.print("HTTP/1.0 403 Forbidden" + '\r' + '\n');
					try{
						shutdown();
					}
					catch(IOException ioe2){
						//closing and flushing failed at the last second? idk what to do.
						return;
					}
					return;
				}


				//add all contents of file to output stream
				String toClient = "HTTP/1.0 200 OK" + '\r' + '\n';
				
				toClient += addHeaders(fileToRead);

				toClient += '\r' + '\n'; //blank line between headers and payload. HEAD needs it too.

				if(command.equals("HEAD")){
					pw.print(toClient);
					shutdown();
					return;
				}

				else if(command.equals("GET")){ //just to be clear.

					String extension = fileToRead.getName().substring(fileToRead.getName().lastIndexOf('.') + 1);

					if(extension.equals("txt") || extension.equals("html")){ //this means its a text document and should be read as such
						while(sc.hasNext()) toClient += sc.next();// + '\r' + '\n'; //adds the payload
					}
			
					else{ //this means that the file should be read as a byte array
						toClient += Files.readAllBytes(fileToRead.toPath());
					}

					pw.print(toClient + '\r' + '\n');
					shutdown();
					return;
			

				}
			}

			else if(command.equals("POST")){

				File fileToRead = new File("." + resource);//.substring(1)
				//this wont fail on file not found, it will happily proceed despite being empty

				if(!fileToRead.exists()){ 
					pw.print("HTTP/1.0 404 Not Found" + '\r' + '\n');
					shutdown();
					return;
				}

				if(fileToRead.isDirectory()){
					pw.print("HTTP/1.0 400 Bad Request" + '\r' + '\n'); // i guess so?
					shutdown();
					return;
				}

				
				int contentLength = getContentLength(request);
				if(contentLength == -1){
					pw.print("HTTP/1.0 411 Length Required" + '\r' + '\n');
					shutdown();
					return;
				}

				
				String contentType = getContentType(request);
				if(contentType == null || !contentType.equals("application/x-www-form-urlencoded")){ //PP2 code cant handle anything else
					pw.print("HTTP/1.0 500 Internal Server Error" + '\r' + '\n');
					shutdown();
					return;
				}


				//at this point, contentLength will be a valid int but it may be negative... hmmm. 400 Bad Format?

				String extension = resource.substring(resource.lastIndexOf('.') + 1);
				if(!extension.equalsIgnoreCase("cgi")){ //SHOULD WE USE EQUALSIGNORECASE? NEEDS TESTING
					pw.print("HTTP/1.0 405 Method Not Allowed" + '\r' + '\n');
					shutdown();
					return;
				}

				// System.out.println(fileToRead.canExecute());
				// if(!fileToRead.canExecute()){
				// 	pw.print("HTTP/1.0 403 Forbidden" + '\r' + '\n');
				// 	shutdown();
				// 	return;
				// }
				//NONE OF THAT WORKS SO WERE JUST GONNA RUN IT, LET IT FAIL WITH SECURITYEXCEPTION, AND CATCH THAT

				String requestPayload = urlEncode(getPayload(request));
				if(requestPayload != null){
					contentLength = requestPayload.length();
				}

				ProcessBuilder pb = new ProcessBuilder(fileToRead.getAbsoluteFile().toString());

				Map<String, String> env = pb.environment();
				env.put("CONTENT_LENGTH", "" + contentLength); //concat'ing an int just casts it to string.
				env.put("SCRIPT_NAME", resource);
				env.put("SERVER_NAME", client.getInetAddress().toString()); //internet address of socket.
				env.put("SERVER_PORT", "" + client.getPort());
				
				String httpfrom = getFrom(request);
				if(httpfrom != null){
					env.put("HTTP_FROM", httpfrom); 
				}
				
				String httpua = getUserAgent(request);
				if(httpua != null){
					env.put("HTTP_USER_AGENT", httpua);
				}

				Process p = pb.start();

				BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

				if(requestPayload != null){
					bw.write(requestPayload);
					bw.flush();
				}
				
				try{
					p.waitFor();
				}
				catch(InterruptedException ie){
					System.out.println("Thread got interrupted while waiting for process.");
					throw new IOException(); //becomes 500 Internal Error
				}

				InputStream processInputStream = p.getInputStream();
				String responsePayload = readInputStream(processInputStream);
				
				String status;			

				if(responsePayload.equals("")){
					status = "HTTP/1.0 204 No Content";
				}
				else status = "HTTP/1.0 200 OK";

				//set headers again
				String headerLines = addHeaders(fileToRead, responsePayload.length());
					
				pw.print(status + '\r' + '\n' + headerLines +'\r' + '\n'+ responsePayload + '\r' + '\n');
				shutdown(); 
				return;


			}

			//use "else if"s here to implement other commands
				
			//if this else is reached, then the command was properly formed but not a valid HTTP 1.0 command. 
			else{
				pw.print("HTTP/1.0 501 Not Implemented" + '\r' + '\n');
				shutdown();
				return;
			}
		}

		

		//this catches all possible crazy unforeseen errors. Nothing in particular.
		catch(IOException ioe){ 
			String cause = ioe.getCause().toString();
			//System.out.println(cause);
			if(cause.contains("error=13,")){ //for POST requests lacking in execute permissions
				pw.print("HTTP/1.0 403 Forbidden"  + '\r' + '\n');
			}
			
			else{
				pw.print("HTTP/1.0 500 Internal Server Error" + '\r' + '\n');
			}
			
			try{
				shutdown();
			}
			catch(IOException ioe2){
				//closing and flushing failed at the last second? idk what to do.
				return;
			}
			return;
		}
	}

}


