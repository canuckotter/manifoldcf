/* $Id$ */

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
package org.apache.lcf.crawler.tests;

import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.agents.interfaces.*;
import org.apache.lcf.crawler.interfaces.*;
import org.apache.lcf.authorities.interfaces.*;
import org.apache.lcf.core.system.LCF;

import java.io.*;
import java.util.*;
import org.junit.*;

/** This is a testing base class that is responsible for setting up/tearing down the agents framework. */
public class TestConnectorBase extends org.apache.lcf.crawler.tests.TestBase
{
  
  protected String[] getConnectorClasses()
  {
    return new String[0];
  }
  
  protected String[] getConnectorNames()
  {
    return new String[0];
  }
  
  protected String[] getAuthorityClasses()
  {
    return new String[0];
  }
  
  protected String[] getAuthorityNames()
  {
    return new String[0];
  }
  
  protected String[] getOutputClasses()
  {
    return new String[0];
  }
  
  protected String[] getOutputNames()
  {
    return new String[0];
  }
  
  @Before
  public void setUp()
    throws Exception
  {
    try
    {
      localCleanUp();
    }
    catch (Exception e)
    {
      System.out.println("Warning: Preclean failed: "+e.getMessage());
    }
    try
    {
      localSetUp();
    }
    catch (Exception e)
    {
      e.printStackTrace();
      throw e;
    }
  }

  protected void localSetUp()
    throws Exception
  {
    
    super.localSetUp();
    
    // Register the connector we're testing
    initialize();
    LCF.initializeEnvironment();
    IThreadContext tc = ThreadContextFactory.make();

    IDBInterface database = DBInterfaceFactory.make(tc,
      LCF.getMasterDatabaseName(),
      LCF.getMasterDatabaseUsername(),
      LCF.getMasterDatabasePassword());
    
    IConnectorManager mgr = ConnectorManagerFactory.make(tc);
    IAuthorityConnectorManager authMgr = AuthorityConnectorManagerFactory.make(tc);
    IJobManager jobManager = JobManagerFactory.make(tc);
    IRepositoryConnectionManager connManager = RepositoryConnectionManagerFactory.make(tc);
    IOutputConnectorManager outputMgr = OutputConnectorManagerFactory.make(tc);
    IOutputConnectionManager outputConnManager = OutputConnectionManagerFactory.make(tc);

    // Deregistration should be done in a transaction
    database.beginTransaction();
    try
    {
      int i;
      
      String[] connectorClasses = getConnectorClasses();
      String[] connectorNames = getConnectorNames();

      i = 0;
      while (i < connectorClasses.length)
      {
        // First, register connector
        mgr.registerConnector(connectorNames[i],connectorClasses[i]);
        // Then, signal to all jobs that might depend on this connector that they can switch state
        // Find the connection names that come with this class
        String[] connectionNames = connManager.findConnectionsForConnector(connectorClasses[i]);
        // For each connection name, modify the jobs to note that the connector is now installed
        jobManager.noteConnectorRegistration(connectionNames);
        i++;
      }
      
      String[] authorityClasses = getAuthorityClasses();
      String[] authorityNames = getAuthorityNames();
      
      i = 0;
      while (i < authorityClasses.length)
      {
        authMgr.registerConnector(authorityNames[i],authorityClasses[i]);
        i++;
      }
      
      String[] outputClasses = getOutputClasses();
      String[] outputNames = getOutputNames();
      
      i = 0;
      while (i < outputClasses.length)
      {
        // First, register connector
        outputMgr.registerConnector(outputNames[i],outputClasses[i]);
        // Then, signal to all jobs that might depend on this connector that they can switch state
        // Find the connection names that come with this class
        String[] connectionNames = outputConnManager.findConnectionsForConnector(outputClasses[i]);
        // For all connection names, notify all agents of the registration
        AgentManagerFactory.noteOutputConnectorRegistration(tc,connectionNames);
        i++;
      }
      
    }
    catch (LCFException e)
    {
      database.signalRollback();
      throw e;
    }
    catch (Error e)
    {
      database.signalRollback();
      throw e;
    }
    finally
    {
      database.endTransaction();
    }
  }
  
  @After
  public void cleanUp()
    throws Exception
  {
    try
    {
      localCleanUp();
    }
    catch (Exception e)
    {
      e.printStackTrace();
      throw e;
    }
  }

  protected void localCleanUp()
    throws Exception
  {
    initialize();
    if (isInitialized())
    {
      // Test the uninstall
      LCF.initializeEnvironment();
      IThreadContext tc = ThreadContextFactory.make();
      
      Exception currentException = null;
      // First, tear down all jobs, connections, authority connections, and output connections.
      try
      {
        IJobManager jobManager = JobManagerFactory.make(tc);
        IRepositoryConnectionManager connMgr = RepositoryConnectionManagerFactory.make(tc);
        IAuthorityConnectionManager authConnMgr = AuthorityConnectionManagerFactory.make(tc);
        IOutputConnectionManager outputMgr = OutputConnectionManagerFactory.make(tc);
        
        // Get a list of the current active jobs
        IJobDescription[] jobs = jobManager.getAllJobs();
        int i = 0;
        while (i < jobs.length)
        {
          IJobDescription desc = jobs[i++];
          // Abort this job, if it is running
          try
          {
            jobManager.manualAbort(desc.getID());
          }
          catch (LCFException e)
          {
            // This generally means that the job was not running
          }
        }
        i = 0;
        while (i < jobs.length)
        {
          IJobDescription desc = jobs[i++];
          // Wait for this job to stop
          while (true)
          {
            JobStatus status = jobManager.getStatus(desc.getID());
            if (status != null)
            {
              int statusValue = status.getStatus();
              switch (statusValue)
              {
              case JobStatus.JOBSTATUS_NOTYETRUN:
              case JobStatus.JOBSTATUS_COMPLETED:
              case JobStatus.JOBSTATUS_ERROR:
                break;
              default:
                LCF.sleep(10000);
                continue;
              }
            }
            break;
          }
        }

        // Now, delete them all
        i = 0;
        while (i < jobs.length)
        {
          IJobDescription desc = jobs[i++];
          try
          {
            jobManager.deleteJob(desc.getID());
          }
          catch (LCFException e)
          {
            // This usually means that the job is already being deleted
          }
        }

        i = 0;
        while (i < jobs.length)
        {
          IJobDescription desc = jobs[i++];
          // Wait for this job to disappear
          while (true)
          {
            JobStatus status = jobManager.getStatus(desc.getID());
            if (status != null)
            {
              LCF.sleep(10000);
              continue;
            }
            break;
          }
        }

        // Now, get a list of the repository connections
        IRepositoryConnection[] connections = connMgr.getAllConnections();
        i = 0;
        while (i < connections.length)
        {
          connMgr.delete(connections[i++].getName());
        }

        // Get a list of authority connections
        IAuthorityConnection[] authorities = authConnMgr.getAllConnections();
        i = 0;
        while (i < authorities.length)
        {
          authConnMgr.delete(authorities[i++].getName());
        }
        
        // Finally, get rid of output connections
        IOutputConnection[] outputs = outputMgr.getAllConnections();
        i = 0;
        while (i < outputs.length)
        {
          outputMgr.delete(outputs[i++].getName());
        }

      }
      catch (Exception e)
      {
        currentException = e;
      }
      try
      {
        IDBInterface database = DBInterfaceFactory.make(tc,
          LCF.getMasterDatabaseName(),
          LCF.getMasterDatabaseUsername(),
          LCF.getMasterDatabasePassword());
        
        IConnectorManager mgr = ConnectorManagerFactory.make(tc);
        IAuthorityConnectorManager authMgr = AuthorityConnectorManagerFactory.make(tc);
        IOutputConnectorManager outputMgr = OutputConnectorManagerFactory.make(tc);
        IOutputConnectionManager outputConnManager = OutputConnectionManagerFactory.make(tc);
        IJobManager jobManager = JobManagerFactory.make(tc);
        IRepositoryConnectionManager connManager = RepositoryConnectionManagerFactory.make(tc);
        
        // Deregistration should be done in a transaction
        database.beginTransaction();
        try
        {
          int i;
          
          String[] connectorClasses = getConnectorClasses();

          i = 0;
          while (i < connectorClasses.length)
          {
            // Find the connection names that come with this class
            String[] connectionNames = connManager.findConnectionsForConnector(connectorClasses[i]);
            // For each connection name, modify the jobs to note that the connector is no longer installed
            jobManager.noteConnectorDeregistration(connectionNames);
            // Now that all jobs have been placed into an appropriate state, actually do the deregistration itself.
            mgr.unregisterConnector(connectorClasses[i]);
            i++;
          }
          
          String[] authorityClasses = getAuthorityClasses();
          
          i = 0;
          while (i < authorityClasses.length)
          {
            authMgr.unregisterConnector(authorityClasses[i]);
            i++;
          }
          
          String[] outputClasses = getOutputClasses();
          
          i = 0;
          while (i < outputClasses.length)
          {
            // Find the connection names that come with this class
            String[] connectionNames = outputConnManager.findConnectionsForConnector(outputClasses[i]);
            // For all connection names, notify all agents of the deregistration
            AgentManagerFactory.noteOutputConnectorDeregistration(tc,connectionNames);
            // Now that all jobs have been placed into an appropriate state, actually do the deregistration itself.
            outputMgr.unregisterConnector(outputClasses[i]);
            i++;
          }
          
        }
        catch (LCFException e)
        {
          database.signalRollback();
          throw e;
        }
        catch (Error e)
        {
          database.signalRollback();
          throw e;
        }
        finally
        {
          database.endTransaction();
        }
      }
      catch (Exception e)
      {
        if (currentException != null)
          currentException = e;
      }
      try
      {
        super.localCleanUp();
      }
      catch (Exception e)
      {
        if (currentException != null)
          currentException = e;
      }
      if (currentException != null)
        throw currentException;
    }
  }

}
