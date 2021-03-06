/* $Id: LivelinkAuthority.java 988245 2010-08-23 18:39:35Z kwright $ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.crawler.connectors.livelink;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.authorities.interfaces.*;
import org.apache.manifoldcf.authorities.system.Logging;
import org.apache.manifoldcf.authorities.system.ManifoldCF;

import java.io.*;
import java.util.*;
import java.net.*;
import java.util.regex.*;

import com.opentext.api.*;

/** This is the Livelink implementation of the IAuthorityConnector interface.
* This is not based on Volant code, but has been developed by me at the behest of
* James Maupin for use at Shell.
*
* Access tokens for livelink are simply user and usergroup node identifiers.  Therefore,
* this class retrieves those using the standard livelink call, being sure to map anything
* that looks like an active directory user name to something that looks like a Livelink
* domain/username form.
*
*/
public class LivelinkAuthority extends org.apache.manifoldcf.authorities.authorities.BaseAuthorityConnector
{
  public static final String _rcsid = "@(#)$Id: LivelinkAuthority.java 988245 2010-08-23 18:39:35Z kwright $";

  // Data from the parameters
  private String serverName = null;
  private String serverPortString = null;
  private int serverPort = -1;
  private String serverUsername = null;
  private String serverPassword = null;

  // Data required for maintaining livelink connection
  private LAPI_USERS LLUsers = null;
  private LLSERVER llServer = null;

  // Cache variables
  private String cacheLifetime = null;
  private String cacheLRUsize = null;
  private long responseLifetime = 60000L;
  private int LRUsize = 1000;

  // Match map for username
  private MatchMap matchMap = null;

  // Retry count.  This is so we can try to install some measure of sanity into situations where LAPI gets confused communicating to the server.
  // So, for some kinds of errors, we just retry for a while hoping it will go away.
  private static final int FAILURE_RETRY_COUNT = 5;

  /** Cache manager. */
  protected ICacheManager cacheManager = null;
  
  // Livelink does not have "deny" permissions, and there is no such thing as a document with no tokens, so it is safe to not have a local "deny" token.
  // However, people feel that a suspenders-and-belt approach is called for, so this restriction has been added.
  // Livelink tokens are numbers, "SYSTEM", or "GUEST", so they can't collide with the standard form.
  private static final String denyToken = "DEAD_AUTHORITY";
  private static final AuthorizationResponse unreachableResponse = new AuthorizationResponse(new String[]{denyToken},
    AuthorizationResponse.RESPONSE_UNREACHABLE);
  private static final AuthorizationResponse userNotFoundResponse = new AuthorizationResponse(new String[]{denyToken},
    AuthorizationResponse.RESPONSE_USERNOTFOUND);

  /** Constructor.
  */
  public LivelinkAuthority()
  {
  }

  /** Set thread context.
  */
  @Override
  public void setThreadContext(IThreadContext tc)
    throws ManifoldCFException
  {
    super.setThreadContext(tc);
    cacheManager = CacheManagerFactory.make(tc);
  }
  
  /** Clear thread context.
  */
  @Override
  public void clearThreadContext()
  {
    super.clearThreadContext();
    cacheManager = null;
  }

  /** Connect.  The configuration parameters are included.
  *@param configParams are the configuration parameters for this connection.
  */
  @Override
  public void connect(ConfigParams configParams)
  {
    super.connect(configParams);

    // First, create server object (llServer)
    serverName = configParams.getParameter(LiveLinkParameters.serverName);
    serverPortString = configParams.getParameter(LiveLinkParameters.serverPort);
    serverUsername = configParams.getParameter(LiveLinkParameters.serverUsername);
    serverPassword = configParams.getObfuscatedParameter(LiveLinkParameters.serverPassword);

    // These have been deprecated
    String userNamePattern = configParams.getParameter(LiveLinkParameters.userNameRegexp);
    String userEvalExpression = configParams.getParameter(LiveLinkParameters.livelinkNameSpec);
    String userNameMapping = configParams.getParameter(LiveLinkParameters.userNameMapping);
    if ((userNameMapping == null || userNameMapping.length() == 0) && userNamePattern != null && userEvalExpression != null)
    {
      // Create a matchmap using the old system
      matchMap = new MatchMap();
      matchMap.appendOldstyleMatchPair(userNamePattern,userEvalExpression);
    }
    else
    {
      if (userNameMapping == null)
        userNameMapping = "(.*)\\\\@([A-Z|a-z|0-9|_|-]*)\\\\.(.*)=$(2)\\$(1l)";
      matchMap = new MatchMap(userNameMapping);
    }

    if (serverPortString == null)
      serverPort = 80;
    else
      serverPort = new Integer(serverPortString).intValue();

    cacheLifetime = configParams.getParameter(LiveLinkParameters.cacheLifetime);
    if (cacheLifetime == null)
      cacheLifetime = "1";
    cacheLRUsize = configParams.getParameter(LiveLinkParameters.cacheLRUSize);
    if (cacheLRUsize == null)
      cacheLRUsize = "1000";    


  }

  protected void attemptToConnect()
    throws ManifoldCFException, ServiceInterruption
  {
    try
    {
      responseLifetime = Long.parseLong(this.cacheLifetime) * 60L * 1000L;
      LRUsize = Integer.parseInt(this.cacheLRUsize);
    }
    catch (NumberFormatException e)
    {
      throw new ManifoldCFException("Cache lifetime or Cache LRU size must be an integer: "+e.getMessage(),e);
    }

    if (LLUsers == null)
    {

      if (Logging.authorityConnectors.isDebugEnabled())
      {
        String passwordExists = (serverPassword!=null && serverPassword.length() > 0)?"password exists":"";
        Logging.authorityConnectors.debug("Livelink: Livelink connection parameters: Server='"+serverName+"'; port='"+serverPortString+"'; user name='"+serverUsername+"'; "+passwordExists);
      }

      int sanityRetryCount = FAILURE_RETRY_COUNT;
      while (true)
      {
        try
        {
          llServer = new LLSERVER(serverName,serverPort,serverUsername,serverPassword);

          LLUsers = new LAPI_USERS(llServer.getLLSession());
          if (Logging.authorityConnectors.isDebugEnabled())
          {
            Logging.authorityConnectors.debug("Livelink: Livelink session created.");
          }
          return;
        }
        catch (RuntimeException e)
        {
          sanityRetryCount = handleLivelinkRuntimeException(e,sanityRetryCount);
        }
      }
    }
  }

  // All methods below this line will ONLY be called if a connect() call succeeded
  // on this instance!

  /** Check connection for sanity.
  */
  @Override
  public String check()
    throws ManifoldCFException
  {
    try
    {
      // Reestablish the session
      LLUsers = null;
      attemptToConnect();
      // Get user info for the crawl user, to make sure it works
      int sanityRetryCount = FAILURE_RETRY_COUNT;
      while (true)
      {
        try
        {
          LLValue userObject = new LLValue();
          int status = LLUsers.GetUserInfo(serverUsername, userObject);
          if (status != 0)
            return "Connection failed: User authentication failed";
          return super.check();
        }
        catch (RuntimeException e)
        {
          sanityRetryCount = handleLivelinkRuntimeException(e,sanityRetryCount);
        }
      }
    }
    catch (ServiceInterruption e)
    {
      return "Temporary service interruption: "+e.getMessage();
    }
    catch (ManifoldCFException e)
    {
      return "Connection failed: "+e.getMessage();
    }
  }

  /** Close the connection.  Call this before discarding the repository connector.
  */
  @Override
  public void disconnect()
    throws ManifoldCFException
  {
    if (llServer != null)
    {
      llServer.disconnect();
      llServer = null;
    }
    LLUsers = null;
    matchMap = null;
    serverName = null;
    serverPortString = null;
    serverPort = -1;
    serverUsername = null;
    serverPassword = null;
    
    cacheLifetime = null;
    cacheLRUsize = null;

    super.disconnect();
  }

  /** Obtain the access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the response tokens (according to the current authority).
  * (Should throws an exception only when a condition cannot be properly described within the authorization response object.)
  */
  @Override
  public AuthorizationResponse getAuthorizationResponse(String userName)
    throws ManifoldCFException
  {
    // Construct a cache description object
    ICacheDescription objectDescription = new AuthorizationResponseDescription(userName,serverName,serverPort,
      serverUsername,serverPassword,responseLifetime,LRUsize);
    
    // Enter the cache
    ICacheHandle ch = cacheManager.enterCache(new ICacheDescription[]{objectDescription},null,null);
    try
    {
      ICacheCreateHandle createHandle = cacheManager.enterCreateSection(ch);
      try
      {
        // Lookup the object
        AuthorizationResponse response = (AuthorizationResponse)cacheManager.lookupObject(createHandle,objectDescription);
        if (response != null)
          return response;
        // Create the object.
        response = getAuthorizationResponseUncached(userName);
        // Save it in the cache
        cacheManager.saveObject(createHandle,objectDescription,response);
        // And return it...
        return response;
      }
      finally
      {
        cacheManager.leaveCreateSection(createHandle);
      }
    }
    finally
    {
      cacheManager.leaveCache(ch);
    }
  }
  
  /** Uncached method to get access tokens for a user name. */
  protected AuthorizationResponse getAuthorizationResponseUncached(String userName)
    throws ManifoldCFException
  {
    // First, do what's necessary to map the user name that comes in to a reasonable
    // Livelink domain\\user combination.

    if (Logging.authorityConnectors.isDebugEnabled())
    {
      Logging.authorityConnectors.debug("Authentication user name = '"+userName+"'");
    }

    // Use the matchMap object to do the translation
    String domainAndUser = matchMap.translate(userName);

    if (Logging.authorityConnectors.isDebugEnabled())
    {
      Logging.authorityConnectors.debug("Livelink: Livelink user name = '"+domainAndUser+"'");
    }

    try
    {
      attemptToConnect();

      int sanityRetryCount = FAILURE_RETRY_COUNT;
      while (true)
      {
        try
        {
          ArrayList list = new ArrayList();

          // Find out if the specified user is a member of the Guest group, or is a member
          // of the System group.
          // Get information about the current user.  This is how we will determine if the
          // user exists, and also what permissions s/he has.
          LLValue userObject = new LLValue();
          int status = LLUsers.GetUserInfo(domainAndUser, userObject);
          if (status == 103101 || status == 401203)
          {
            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("Livelink: Livelink user '"+domainAndUser+"' does not exist");
            return userNotFoundResponse;
          }

          if (status != 0)
          {
            Logging.authorityConnectors.warn("Livelink: User '"+domainAndUser+"' GetUserInfo error # "+Integer.toString(status)+" "+llServer.getErrors());
            // The server is probably down.
            return unreachableResponse;
          }

          int deleted = userObject.toInteger("Deleted");
          if (deleted == 1)
          {
            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("Livelink: Livelink user '"+domainAndUser+"' has been deleted");
            // Since the user cannot become undeleted, then this should be treated as 'user does not exist'.
            return userNotFoundResponse;
          }
          int privs = userObject.toInteger("UserPrivileges");
          if ((privs & LAPI_USERS.PRIV_PERM_WORLD) == LAPI_USERS.PRIV_PERM_WORLD)
            list.add("GUEST");
          if ((privs & LAPI_USERS.PRIV_PERM_BYPASS) == LAPI_USERS.PRIV_PERM_BYPASS)
            list.add("SYSTEM");

          LLValue childrenObjects = new LLValue();
          status = LLUsers.ListRights(LAPI_USERS.USER, domainAndUser, childrenObjects);
          if (status == 103101 || status == 401203)
          {
            if (Logging.authorityConnectors.isDebugEnabled())
              Logging.authorityConnectors.debug("Livelink: Livelink error looking up user rights for '"+domainAndUser+"' - user does not exist");
            return userNotFoundResponse;
          }

          if (status != 0)
          {
            // If the user doesn't exist, return null.  Right now, not sure how to figure out the
            // right error code, so just stuff it in the log.
            Logging.authorityConnectors.warn("Livelink: For user '"+domainAndUser+"', ListRights error # "+Integer.toString(status)+" "+llServer.getErrors());
            // An error code at this level has to indicate a suddenly unreachable authority
            return unreachableResponse;
          }

          // Go through the individual objects, and get their IDs.  These id's will be the access tokens
          int size;

          if (childrenObjects.isRecord())
            size = 1;
          else if (childrenObjects.isTable())
            size = childrenObjects.size();
          else
            size = 0;

          // We need also to add in support for the special rights objects.  These are:
          // -1: RIGHT_WORLD
          // -2: RIGHT_SYSTEM
          // -3: RIGHT_OWNER
          // -4: RIGHT_GROUP
          //
          // RIGHT_WORLD means guest access.
          // RIGHT_SYSTEM is "Public Access".
          // RIGHT_OWNER is access by the owner of the object.
          // RIGHT_GROUP is access by a member of the base group containing the owner
          //
          // These objects are returned by the corresponding GetObjectRights() call made during
          // the ingestion process.  We have to figure out how to map these to things that are
          // the equivalent of acls.

          // Idea:
          // 1) RIGHT_WORLD is based on some property of the user.
          // 2) RIGHT_SYSTEM is based on some property of the user.
          // 3) RIGHT_OWNER and RIGHT_GROUP are managed solely in the ingestion side of the world.

          // NOTE:  It turns out that -1 and -2 are in fact returned as part of the list of
          // rights requested above.  They get mapped to special keywords already in the above
          // code, so it *may* be reasonable to filter them from here.  It's not a real problem because
          // it's effectively just a duplicate of what we are doing.

          int j = 0;
          while (j < size)
          {
            int token = childrenObjects.toInteger(j, "ID");
            list.add(Integer.toString(token));
            j++;
          }
          String[] rval = new String[list.size()];
          j = 0;
          while (j < rval.length)
          {
            rval[j] = (String)list.get(j);
            j++;
          }

          return new AuthorizationResponse(rval,AuthorizationResponse.RESPONSE_OK);
        }
        catch (RuntimeException e)
        {
          sanityRetryCount = handleLivelinkRuntimeException(e,sanityRetryCount);
        }
      }
    }
    catch (ServiceInterruption e)
    {
      Logging.authorityConnectors.warn("Livelink: Server seems to be down: "+e.getMessage(),e);
      return unreachableResponse;
    }
  }

  /** Obtain the default access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the default response tokens, presuming that the connect method fails.
  */
  @Override
  public AuthorizationResponse getDefaultAuthorizationResponse(String userName)
  {
    // The default response if the getConnection method fails
    return unreachableResponse;
  }

  // UI support methods.
  //
  // These support methods are involved in setting up authority connection configuration information. The configuration methods cannot assume that the
  // current authority object is connected.  That is why they receive a thread context argument.
    
  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to add the required tabs to the list, and to output any
  * javascript methods that might be needed by the configuration editing HTML.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add(Messages.getString(locale,"LivelinkConnector.Server"));
    tabsArray.add(Messages.getString(locale,"LivelinkConnector.UserMapping"));
    tabsArray.add(Messages.getString(locale,"LivelinkConnector.Cache"));

    out.print(
"<script type=\"text/javascript\">\n"+
"<!--\n"+
"function checkConfig()\n"+
"{\n"+
"  if (editconnection.serverport.value != \"\" && !isInteger(editconnection.serverport.value))\n"+
"  {\n"+
"    alert(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.AValidNumberIsRequired") + ");\n"+
"    editconnection.serverport.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.usernameregexp.value != \"\" && !isRegularExpression(editconnection.usernameregexp.value))\n"+
"  {\n"+
"    alert(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.UserNameRegularExpressionMustBeValidRegularExpression") + ");\n"+
"    editconnection.usernameregexp.focus();\n"+
"    return false;\n"+
"  }\n"+
"  return true;\n"+
"}\n"+
"\n"+
"function checkConfigForSave()\n"+
"{\n"+
"  if (editconnection.servername.value == \"\")\n"+
"  {\n"+
"    alert(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.EnterALivelinkServerName") + ");\n"+
"    SelectTab(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.Server") + ");\n"+
"    editconnection.servername.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.serverport.value == \"\")\n"+
"  {\n"+
"    alert(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.AServerPortNumberIsRequired") + ");\n"+
"    SelectTab(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.Server") + ");\n"+
"    editconnection.serverport.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.usernameregexp.value == \"\")\n"+
"  {\n"+
"    alert(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.UserNameRegularExpressionCannotBeNull") + ");\n"+
"    SelectTab(" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.UserMapping") + ");\n"+
"    editconnection.usernameregexp.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.cachelifetime.value == \"\")\n"+
"  {\n"+
"    alert(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.CacheLifetimeCannotBeNull") + "\");\n"+
"    SelectTab(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.Cache") + "\");\n"+
"    editconnection.cachelifetime.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.cachelifetime.value != \"\" && !isInteger(editconnection.cachelifetime.value))\n"+
"  {\n"+
"    alert(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.CacheLifetimeMustBeAnInteger") + "\");\n"+
"    SelectTab(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.Cache") + "\");\n"+
"    editconnection.cachelifetime.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.cachelrusize.value == \"\")\n"+
"  {\n"+
"    alert(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.CacheLRUSizeCannotBeNull") + "\");\n"+
"    SelectTab(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.Cache") + "\");\n"+
"    editconnection.cachelrusize.focus();\n"+
"    return false;\n"+
"  }\n"+
"  if (editconnection.cachelrusize.value != \"\" && !isInteger(editconnection.cachelrusize.value))\n"+
"  {\n"+
"    alert(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.CacheLRUSizeMustBeAnInteger") + "\");\n"+
"    SelectTab(\"" + Messages.getBodyJavascriptString(locale,"LivelinkConnector.Cache") + "\");\n"+
"    editconnection.cachelrusize.focus();\n"+
"    return false;\n"+
"  }\n"+
"  return true;\n"+
"}\n"+
"\n"+
"//-->\n"+
"</script>\n"
    );
  }
  
  /** Output the configuration body section.
  * This method is called in the body section of the authority connector's configuration page.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editconnection".
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, String tabName)
    throws ManifoldCFException, IOException
  {
    String serverName = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverName);
    if (serverName == null)
      serverName = "localhost";
    
    String serverPort = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverPort);
    if (serverPort == null)
      serverPort = "2099";
    
    String serverUserName = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverUsername);
    if (serverUserName == null)
      serverUserName = "";
    
    String serverPassword = parameters.getObfuscatedParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverPassword);
    if (serverPassword == null)
      serverPassword = "";
    
    String cacheLifetime = parameters.getParameter(LiveLinkParameters.cacheLifetime);
    if (cacheLifetime == null)
      cacheLifetime = "1";
    
    String cacheLRUsize = parameters.getParameter(LiveLinkParameters.cacheLRUSize);
    if (cacheLRUsize == null)
      cacheLRUsize = "1000";    

    org.apache.manifoldcf.crawler.connectors.livelink.MatchMap matchMap = null;
    String usernameRegexp = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.userNameRegexp);
    String livelinkUserExpr = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.livelinkNameSpec);
    if (usernameRegexp != null && usernameRegexp.length() > 0 && livelinkUserExpr != null)
    {
      // Old-style configuration.  Convert to the new.
      matchMap = new org.apache.manifoldcf.crawler.connectors.livelink.MatchMap();
      matchMap.appendOldstyleMatchPair(usernameRegexp,livelinkUserExpr);
    }
    else
    {
      // New style configuration.
      String userNameMapping = parameters.getParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.userNameMapping);
      if (userNameMapping == null)
        userNameMapping = "^(.*)\\\\@([A-Z|a-z|0-9|_|-]*)\\\\.(.*)$=$(2)\\\\$(1l)";
      matchMap = new org.apache.manifoldcf.crawler.connectors.livelink.MatchMap(userNameMapping);
    }

    usernameRegexp = matchMap.getMatchString(0);
    livelinkUserExpr = matchMap.getReplaceString(0);

    // The "Server" tab
    if (tabName.equals(Messages.getString(locale,"LivelinkConnector.Server")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.ServerName") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"64\" name=\"servername\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(serverName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.ServerPort") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"serverport\" value=\""+serverPort+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.ServerUserName") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"32\" name=\"serverusername\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(serverUserName)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.ServerPassword") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"password\" size=\"32\" name=\"serverpassword\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(serverPassword)+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for Server tab
      out.print(
"<input type=\"hidden\" name=\"servername\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(serverName)+"\"/>\n"+
"<input type=\"hidden\" name=\"serverport\" value=\""+serverPort+"\"/>\n"+
"<input type=\"hidden\" name=\"serverusername\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(serverUserName)+"\"/>\n"+
"<input type=\"hidden\" name=\"serverpassword\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(serverPassword)+"\"/>\n"
      );
    }

    // The "User Mapping" tab
    if (tabName.equals(Messages.getString(locale,"LivelinkConnector.UserMapping")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.UserNameRegularExpression") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"40\" name=\"usernameregexp\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(usernameRegexp)+"\"/></td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.LivelinkUserExpression") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"40\" name=\"livelinkuserexpr\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(livelinkUserExpr)+"\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for "User Mapping" tab
      out.print(
"<input type=\"hidden\" name=\"usernameregexp\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(usernameRegexp)+"\"/>\n"+
"<input type=\"hidden\" name=\"livelinkuserexpr\" value=\""+org.apache.manifoldcf.ui.util.Encoder.attributeEscape(livelinkUserExpr)+"\"/>\n"
      );
    }
    
    // "Cache" tab
    if(tabName.equals(Messages.getString(locale,"LivelinkConnector.Cache")))
    {
      out.print(
"<table class=\"displaytable\">\n"+
"  <tr><td class=\"separator\" colspan=\"2\"><hr/></td></tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.CacheLifetime") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"cachelifetime\" value=\"" + org.apache.manifoldcf.ui.util.Encoder.attributeEscape(cacheLifetime) + "\"/> " + Messages.getBodyString(locale,"LivelinkConnector.minutes") + "</td>\n"+
"  </tr>\n"+
"  <tr>\n"+
"    <td class=\"description\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.CacheLRUSize") + "</nobr></td>\n"+
"    <td class=\"value\"><input type=\"text\" size=\"5\" name=\"cachelrusize\" value=\"" + org.apache.manifoldcf.ui.util.Encoder.attributeEscape(cacheLRUsize) + "\"/></td>\n"+
"  </tr>\n"+
"</table>\n"
      );
    }
    else
    {
      // Hiddens for "Cache" tab
      out.print(
"<input type=\"hidden\" name=\"cachelifetime\" value=\"" + org.apache.manifoldcf.ui.util.Encoder.attributeEscape(cacheLifetime) + "\"/>\n"+
"<input type=\"hidden\" name=\"cachelrusize\" value=\"" + org.apache.manifoldcf.ui.util.Encoder.attributeEscape(cacheLRUsize) + "\"/>\n"
      );
    }

  }
  
  /** Process a configuration post.
  * This method is called at the start of the authority connector's configuration page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the configuration parameters accordingly.
  * The name of the posted form is "editconnection".
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the connection (and cause a redirection to an error page).
  */
  @Override
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException
  {
    String serverName = variableContext.getParameter("servername");
    if (serverName != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverName,serverName);
    String serverPort = variableContext.getParameter("serverport");
    if (serverPort != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverPort,serverPort);
    String serverUserName = variableContext.getParameter("serverusername");
    if (serverUserName != null)
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverUsername,serverUserName);
    String serverPassword = variableContext.getParameter("serverpassword");
    if (serverPassword != null)
      parameters.setObfuscatedParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.serverPassword,serverPassword);
    String usernameRegexp = variableContext.getParameter("usernameregexp");
    String livelinkUserExpr = variableContext.getParameter("livelinkuserexpr");
    if (usernameRegexp != null && livelinkUserExpr != null)
    {
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.userNameRegexp,null);
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.livelinkNameSpec,null);

      org.apache.manifoldcf.crawler.connectors.livelink.MatchMap matchMap = new org.apache.manifoldcf.crawler.connectors.livelink.MatchMap();
      matchMap.appendMatchPair(usernameRegexp,livelinkUserExpr);
      parameters.setParameter(org.apache.manifoldcf.crawler.connectors.livelink.LiveLinkParameters.userNameMapping,matchMap.toString());
    }

    String cacheLifetime = variableContext.getParameter("cachelifetime");
    if (cacheLifetime != null)
      parameters.setParameter(LiveLinkParameters.cacheLifetime,cacheLifetime);

    String cacheLRUsize = variableContext.getParameter("cachelrusize");
    if (cacheLRUsize != null)
      parameters.setParameter(LiveLinkParameters.cacheLRUSize,cacheLRUsize);

    return null;
  }
  
  /** View configuration.
  * This method is called in the body section of the authority connector's view configuration page.  Its purpose is to present the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException, IOException
  {
    out.print(
"<table class=\"displaytable\">\n"+
"  <tr>\n"+
"    <td class=\"description\" colspan=\"1\"><nobr>" + Messages.getBodyString(locale,"LivelinkConnector.Parameters") + "</nobr></td>\n"+
"    <td class=\"value\" colspan=\"3\">\n"
    );
    Iterator iter = parameters.listParameters();
    while (iter.hasNext())
    {
      String param = (String)iter.next();
      String value = parameters.getParameter(param);
      if (param.length() >= "password".length() && param.substring(param.length()-"password".length()).equalsIgnoreCase("password"))
      {
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(param)+"=********</nobr><br/>\n"
        );
      }
      else if (param.length() >="keystore".length() && param.substring(param.length()-"keystore".length()).equalsIgnoreCase("keystore"))
      {
        IKeystoreManager kmanager = KeystoreManagerFactory.make("",value);
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(param)+"=&lt;"+Integer.toString(kmanager.getContents().length)+" certificate(s)&gt;</nobr><br/>\n"
        );
      }
      else
      {
        out.print(
"      <nobr>"+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(param)+"="+org.apache.manifoldcf.ui.util.Encoder.bodyEscape(value)+"</nobr><br/>\n"
        );
      }
    }
    out.print(
"    </td>\n"+
"  </tr>\n"+
"</table>\n"
    );
  }

  /** Interpret runtimeexception to search for livelink API errors.  Throws an appropriately reinterpreted exception, or
  * just returns if the exception indicates that a short-cycle retry attempt should be made.  (In that case, the appropriate
  * wait has been already performed).
  *@param e is the RuntimeException caught
  */
  protected int handleLivelinkRuntimeException(RuntimeException e, int sanityRetryCount)
    throws ManifoldCFException, ServiceInterruption
  {
    if (
      e instanceof com.opentext.api.LLHTTPAccessDeniedException ||
      e instanceof com.opentext.api.LLHTTPClientException ||
      e instanceof com.opentext.api.LLHTTPServerException ||
      e instanceof com.opentext.api.LLIndexOutOfBoundsException ||
      e instanceof com.opentext.api.LLNoFieldSpecifiedException ||
      e instanceof com.opentext.api.LLNoValueSpecifiedException ||
      e instanceof com.opentext.api.LLSecurityProviderException ||
      e instanceof com.opentext.api.LLUnknownFieldException
    )
    {
      String details = llServer.getErrors();
      throw new ManifoldCFException("Livelink API error: "+e.getMessage()+((details==null)?"":"; "+details),e,ManifoldCFException.REPOSITORY_CONNECTION_ERROR);
    }
    else if (
      e instanceof com.opentext.api.LLBadServerCertificateException ||
      e instanceof com.opentext.api.LLHTTPCGINotFoundException ||
      e instanceof com.opentext.api.LLCouldNotConnectHTTPException ||
      e instanceof com.opentext.api.LLHTTPForbiddenException ||
      e instanceof com.opentext.api.LLHTTPProxyAuthRequiredException ||
      e instanceof com.opentext.api.LLHTTPRedirectionException ||
      e instanceof com.opentext.api.LLUnsupportedAuthMethodException ||
      e instanceof com.opentext.api.LLWebAuthInitException
    )
    {
      String details = llServer.getErrors();
      throw new ManifoldCFException("Livelink API error: "+e.getMessage()+((details==null)?"":"; "+details),e);
    }
    else if (e instanceof com.opentext.api.LLIllegalOperationException)
    {
      // This usually means that LAPI has had a minor communication difficulty but hasn't reported it accurately.
      // We *could* throw a ServiceInterruption, but OpenText recommends to just retry almost immediately.
      String details = llServer.getErrors();
      return assessRetry(sanityRetryCount,new ManifoldCFException("Livelink API illegal operation error: "+e.getMessage()+((details==null)?"":"; "+details),e));
    }
    else if (e instanceof com.opentext.api.LLIOException)
    {
      // LAPI is returning errors that are not terribly explicit, and I don't have control over their wording, so check that server can be resolved by DNS,
      // so that a better error message can be returned.
      try
      {
        InetAddress.getByName(serverName);
      }
      catch (UnknownHostException e2)
      {
        throw new ManifoldCFException("Server name '"+serverName+"' cannot be resolved",e2);
      }

      throw new ServiceInterruption("Transient error: "+e.getMessage(),e,System.currentTimeMillis()+5*60000L,System.currentTimeMillis()+12*60*60000L,-1,true);
    }
    else
      throw e;
  }

  /** Do a retry, or throw an exception if the retry count has been exhausted
  */
  protected static int assessRetry(int sanityRetryCount, ManifoldCFException e)
    throws ManifoldCFException
  {
    if (sanityRetryCount == 0)
    {
      throw e;
    }

    sanityRetryCount--;

    try
    {
      ManifoldCF.sleep(1000L);
    }
    catch (InterruptedException e2)
    {
      throw new ManifoldCFException(e2.getMessage(),e2,ManifoldCFException.INTERRUPTED);
    }
    // Exit the method
    return sanityRetryCount;

  }

  protected static StringSet emptyStringSet = new StringSet();
  
  /** This is the cache object descriptor for cached access tokens from
  * this connector.
  */
  protected static class AuthorizationResponseDescription extends org.apache.manifoldcf.core.cachemanager.BaseDescription
  {
    /** The user name associated with the access tokens */
    protected String userName;
    
    // The server connection parameters
    protected String serverName;
    protected int serverPort;
    protected String serverUsername;
    protected String serverPassword;

    protected long responseLifetime;
    
    /** The expiration time */
    protected long expirationTime = -1;
    
    /** Constructor. */
    public AuthorizationResponseDescription(String userName, String serverName, int serverPort,
      String serverUsername, String serverPassword, long responseLifetime, int LRUsize)
    {
      super("LiveLinkAuthority",LRUsize);
      this.userName = userName;
      this.serverName = serverName;
      this.serverPort = serverPort;
      this.serverUsername = serverUsername;
      this.serverPassword = serverPassword;
      this.responseLifetime = responseLifetime;
    }

    /** Return the invalidation keys for this object. */
    public StringSet getObjectKeys()
    {
      return emptyStringSet;
    }

    /** Get the critical section name, used for synchronizing the creation of the object */
    public String getCriticalSectionName()
    {
      return getClass().getName() + "-" + userName + "-" + serverName +
        "-" + Integer.toString(serverPort) + "-" + serverUsername + "-" + serverPassword;
    }

    /** Return the object expiration interval */
    public long getObjectExpirationTime(long currentTime)
    {
      if (expirationTime == -1)
        expirationTime = currentTime + responseLifetime;
      return expirationTime;
    }

    public int hashCode()
    {
      return userName.hashCode() + serverName.hashCode() + new Integer(serverPort).hashCode() +
        serverUsername.hashCode() + serverPassword.hashCode();
    }
    
    public boolean equals(Object o)
    {
      if (!(o instanceof AuthorizationResponseDescription))
        return false;
      AuthorizationResponseDescription ard = (AuthorizationResponseDescription)o;
      return ard.userName.equals(userName) && ard.serverName.equals(serverName) && ard.serverPort == serverPort &&
        ard.serverUsername.equals(serverUsername) && ard.serverPassword.equals(serverPassword);
    }
    
  }

}


