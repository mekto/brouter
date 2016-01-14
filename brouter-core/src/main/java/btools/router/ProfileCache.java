/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.io.File;

import btools.expressions.BExpressionContextGlobal;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;

public final class ProfileCache 
{
  private static BExpressionContextWay expctxWay;
  private static BExpressionContextNode expctxNode;
 
  private static File lastLookupFile;
  private static File lastProfileFile;
  private static String lastProfile;

  private static long lastLookupTimestamp;
  private static long lastProfileTimestamp;
  
  private static boolean profilesBusy;

  public static synchronized boolean parseProfile( RoutingContext rc )
  {
      String profileBaseDir = System.getProperty( "profileBaseDir" );
      String profile = null;
      File profileFile = null;
      File profileDir;
 
      if ( rc.rawProfile != null ) {
          profile = rc.rawProfile;
          profileDir = new File( profileBaseDir );
      }
      else if ( profileBaseDir == null )
      {
        profileDir = new File( rc.localFunction ).getParentFile();
        profileFile = new File( rc.localFunction ) ;
      }
      else
      {
        profileDir = new File( profileBaseDir );
        profileFile = new File( profileDir, rc.localFunction + ".brf" ) ;
      }
      File lookupFile = new File( profileDir, "lookups.dat" );

      // check for re-use
      if ( expctxWay != null && expctxNode != null && !profilesBusy )
      {
        boolean canReuse = lookupFile.equals( lastLookupFile ) && lookupFile.lastModified() ==  lastLookupTimestamp
          && ( ( profile != null && profile.equals(lastProfile) ) || ( profileFile != null && profileFile.equals( lastProfileFile ) && profileFile.lastModified() == lastProfileTimestamp ) );

        if (canReuse) {
          rc.expctxWay = expctxWay;
          rc.expctxNode = expctxNode;
          profilesBusy = true;
          rc.readGlobalConfig(expctxWay);
          return true;
        }
      }
      
      BExpressionMetaData meta = new BExpressionMetaData();
      
      BExpressionContextGlobal expctxGlobal = new BExpressionContextGlobal( meta );
      rc.expctxWay = new BExpressionContextWay( rc.serversizing ? 262144 : 8192, meta );
      rc.expctxNode = new BExpressionContextNode( rc.serversizing ?  16384 : 2048, meta );
      
      meta.readMetaData( new File( profileDir, "lookups.dat" ) );

      if (profile != null)
        expctxGlobal.parseString( profile, null );
      else
        expctxGlobal.parseFile( profileFile, null );
      expctxGlobal.evaluate( new int[0] );
      rc.readGlobalConfig(expctxGlobal);

      if (profile != null)
      {
        rc.expctxWay.parseString( profile, "global" );
        rc.expctxNode.parseString( profile, "global" );
      }
      else
      {
        rc.expctxWay.parseFile( profileFile, "global" );
        rc.expctxNode.parseFile( profileFile, "global" );
      }
      
      if ( profileFile != null )
        lastProfileTimestamp = profileFile.lastModified();
      else
        lastProfileTimestamp = 0;
      lastLookupTimestamp = lookupFile.lastModified();
      lastProfileFile = profileFile;
      lastLookupFile = lookupFile;
      lastProfile = profile;
      expctxWay = rc.expctxWay;
      expctxNode = rc.expctxNode;
      profilesBusy = true;
      return false;
  }

  public static synchronized void releaseProfile( RoutingContext rc )
  {
    // only the thread that holds the cached instance can release it
    if ( rc.expctxWay == expctxWay && rc.expctxNode == expctxNode )
    {
      profilesBusy = false;
    }
    rc.expctxWay = null;
    rc.expctxNode = null;
  }

}
