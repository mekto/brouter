package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.Date;
import java.util.Locale;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.server.request.ProfileUploadHandler;
import btools.server.request.RequestHandler;
import btools.server.request.ServerHandler;

public class RouteServer extends Thread
{
  public static final String PROFILE_UPLOAD_URL = "/brouter/profile";

	public ServiceContext serviceContext;

  private Socket clientSocket = null;
  private RoutingEngine cr = null;
  private volatile boolean terminated;

  public void stopRouter()
  {
    RoutingEngine e = cr;
    if ( e != null ) e.terminate();
  }
    
  private static DateFormat tsFormat = new SimpleDateFormat( "dd.MM.yy HH:mm", new Locale( "en", "US" ) );

  private static String formattedTimestamp()
  {
    synchronized( tsFormat )
    {
      return tsFormat.format( new Date( System.currentTimeMillis() ) );
    }
  }

  public void run()
  {
          BufferedReader br = null;
          BufferedWriter bw = null;
          try
          {
            br = new BufferedReader( new InputStreamReader( clientSocket.getInputStream() ) );
            bw = new BufferedWriter( new OutputStreamWriter( clientSocket.getOutputStream() ) );

            // we just read the first line
            String getline = br.readLine();
            if ( getline == null || getline.startsWith("GET /favicon.ico") )
            {
            	return;
            }

            InetAddress ip = clientSocket.getInetAddress();
            System.out.println( formattedTimestamp() + " ip=" + (ip==null ? "null" : ip.toString() ) + " -> " + getline );

            String url = getline.split(" ")[1];
            HashMap<String,String> params = getUrlParams(url);

            long maxRunningTime = getMaxRunningTime();

            RequestHandler handler;
            if ( getline.startsWith("OPTIONS") ) {
                // handle CORS preflight request (Safari)
                String corsHeaders = "Access-Control-Allow-Methods: GET, POST, OPTIONS\n"
                                   + "Access-Control-Max-Age: 86400\n"
                                   + "Access-Control-Allow-Headers: Content-Type\n";
                writeHttpHeader( bw, "text/plain", null, corsHeaders );
                bw.flush();
                return;
            }
            else if ( params.containsKey( "lonlats" ) && ( params.containsKey( "profile" ) || getline.startsWith( "POST" ) ) )
            {
            	if ( getline.startsWith( "POST" ) )
                {
                    String rawProfile = getPostBody(br);
                    params.put("rawProfile", rawProfile);
                }
                handler = new ServerHandler( serviceContext, params );
            }
            else if ( url.startsWith( PROFILE_UPLOAD_URL ) )
            {
                writeHttpHeader(bw, "application/json");

                String profileId = null;
                if ( url.length() > PROFILE_UPLOAD_URL.length() + 1 )
                {
                  // e.g. /brouter/profile/custom_1400767688382
                  profileId = url.substring(PROFILE_UPLOAD_URL.length() + 1);
                }

                ProfileUploadHandler uploadHandler = new ProfileUploadHandler( serviceContext );
                uploadHandler.handlePostRequest( profileId, br, bw );

                bw.flush();
                return;
            }
            else
            {
            	throw new IllegalArgumentException( "unknown request syntax: " + getline );
            }
            RoutingContext rc = handler.readRoutingContext();
            List<OsmNodeNamed> wplist = handler.readWayPointList();
            
            try {
                cr = new RoutingEngine( null, null, serviceContext.segmentDir, wplist, rc );
            }
            catch ( IllegalArgumentException e ) {
              writeHttpHeader(bw);
              bw.write( e.getMessage() );
              bw.write( "\n" );
              
              e.printStackTrace();
            }
            
            if ( cr != null ) {
              cr.quite = true;
              cr.doRun( maxRunningTime );

              if ( cr.getErrorMessage() != null )
              {
                writeHttpHeader(bw);
                bw.write( cr.getErrorMessage() );
                bw.write( "\n" );
              }
              else
              {
                OsmTrack track = cr.getFoundTrack();
                writeHttpHeader(bw, handler.getMimeType(), handler.getFileName());
                if ( track != null )
                {
                  bw.write( handler.formatTrack(track) );
                }
              }                
            }
            bw.flush();
          }
          catch (Throwable e)
          {
             System.out.println("RouteServer got exception (will continue): "+e);
             e.printStackTrace();
          }
          finally
          {
              cr = null;
              if ( br != null ) try { br.close(); } catch( Exception e ) {}
              if ( bw != null ) try { bw.close(); } catch( Exception e ) {}
              if ( clientSocket != null ) try { clientSocket.close(); } catch( Exception e ) {}
              terminated = true;
          }
  }

  public static void main(String[] args) throws Exception
  {
        System.out.println("BRouter 1.3.2 / 31102015");
        if ( args.length != 5 )
        {
          System.out.println("serve BRouter protocol");
          System.out.println("usage: java RouteServer <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads>");
          return;
        }

        ServiceContext serviceContext = new ServiceContext();
        serviceContext.segmentDir = args[0];
        serviceContext.profileDir = args[1];
        System.setProperty( "profileBaseDir", serviceContext.profileDir );
        serviceContext.customProfileDir = args[2];

        int maxthreads = Integer.parseInt( args[4] );

        TreeMap<Long,RouteServer> threadMap = new TreeMap<Long,RouteServer>();

        ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[3]));
        long last_ts = 0;
        for (;;)
        {
          Socket clientSocket = serverSocket.accept();
          RouteServer server = new RouteServer();
          server.serviceContext = serviceContext;
          server.clientSocket = clientSocket;

          // cleanup thread list
          for(;;)
          {
            boolean removedItem = false;
            for (Map.Entry<Long,RouteServer> e : threadMap.entrySet())
            {
              if ( e.getValue().terminated )
              {
                threadMap.remove( e.getKey() );
                removedItem = true;
                break;
              }
            }
            if ( !removedItem ) break;
          }

          // kill thread if limit reached
          if ( threadMap.size() >= maxthreads )
          {
             Long k = threadMap.firstKey();
             RouteServer victim = threadMap.get( k );
             threadMap.remove( k );
             victim.stopRouter();
          }

          long ts = System.currentTimeMillis();
          while ( ts <=  last_ts ) ts++;
          threadMap.put( Long.valueOf( ts ), server );
          last_ts = ts;
          server.start();
        }
  }


  private static HashMap<String,String> getUrlParams( String url )
  {
	  HashMap<String,String> params = new HashMap<String,String>();
	  StringTokenizer tk = new StringTokenizer( url, "?&" );
	  while( tk.hasMoreTokens() )
	  {
	    String t = tk.nextToken();
	    StringTokenizer tk2 = new StringTokenizer( t, "=" );
	    if ( tk2.hasMoreTokens() )
	    {
	      String key = tk2.nextToken();
	      if ( tk2.hasMoreTokens() )
	      {
	        String value = tk2.nextToken();
	        params.put( key, value );
	      }
	    }
	  }
	  return params;
  }
    
  private static String getPostBody( BufferedReader br ) throws IOException
  {
    StringBuilder buffer = new StringBuilder();
    Integer contentLength = 0;
    String line;
    int b;
    
    while ( br.ready() && (line = br.readLine()) != null )
    {
        if (line.startsWith("Content-Length: "))
            contentLength = Integer.parseInt(line.substring(16));

        // skip headers
        // blank line separates headers from body
        if ( line.length() == 0 )
            break;
    }
    
    int pos = 0;
    while ( pos++ < contentLength && (b = br.read()) != -1 ) {
        buffer.append((char) b);
    }
    
    return buffer.toString();
  }

  private static long getMaxRunningTime() {
    long maxRunningTime = 60000;
    String sMaxRunningTime = System.getProperty( "maxRunningTime" );
    if ( sMaxRunningTime != null )
    {
      maxRunningTime = Integer.parseInt( sMaxRunningTime ) * 1000;
    }
    return maxRunningTime;
  }

  private static void writeHttpHeader( BufferedWriter bw ) throws IOException
  {
    writeHttpHeader( bw, "text/plain" );
  }

  private static void writeHttpHeader( BufferedWriter bw, String mimeType ) throws IOException
  {
    writeHttpHeader( bw, mimeType, null );
  }

  private static void writeHttpHeader( BufferedWriter bw, String mimeType, String fileName ) throws IOException
  {
    writeHttpHeader( bw, mimeType, fileName, null);
  }

  private static void writeHttpHeader( BufferedWriter bw, String mimeType, String fileName, String headers ) throws IOException
  {
    // http-header
    bw.write( "HTTP/1.1 200 OK\n" );
    bw.write( "Connection: close\n" );
    bw.write( "Content-Type: " + mimeType + "; charset=utf-8\n" );
    if ( fileName != null )
    {
      bw.write( "Content-Disposition: attachment; filename=" + fileName + "\n" );
    }
    bw.write( "Access-Control-Allow-Origin: *\n" );
    if ( headers != null )
    {
      bw.write( headers );
    }
    bw.write( "\n" );
  }
}
