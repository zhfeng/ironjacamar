/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.jca.core.connectionmanager.pool.mcp;

import org.jboss.jca.common.JBossResourceException;
import org.jboss.jca.core.connectionmanager.listener.ConnectionListener;
import org.jboss.jca.core.connectionmanager.listener.ConnectionListenerFactory;
import org.jboss.jca.core.connectionmanager.listener.ConnectionState;
import org.jboss.jca.core.connectionmanager.pool.SubPoolContext;
import org.jboss.jca.core.connectionmanager.pool.api.Pool;
import org.jboss.jca.core.connectionmanager.pool.api.PoolConfiguration;
import org.jboss.jca.core.connectionmanager.pool.idle.IdleRemover;
import org.jboss.jca.core.connectionmanager.pool.validator.ConnectionValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.resource.ResourceException;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionFactory;
import javax.resource.spi.RetryableUnavailableException;
import javax.resource.spi.ValidatingManagedConnectionFactory;
import javax.security.auth.Subject;

import org.jboss.logging.Logger;
import org.jboss.util.UnreachableStatementException;

/**
 * The internal pool implementation
 *
 * @author <a href="mailto:d_jencks@users.sourceforge.net">David Jencks</a>
 * @author <a href="mailto:adrian@jboss.org">Adrian Brock</a>
 * @author <a href="mailto:weston.price@jboss.com">Weston Price</a>
 * @author <a href="mailto:jesper.pedersen@jboss.org">Jesper Pedersen</a>
 * @version $Revision: 107890 $
 */
public class SemaphoreArrayListManagedConnectionPool implements ManagedConnectionPool
{
   /** The log */
   private Logger log;

   /** Whether trace is enabled */
   private boolean trace;

   /** The managed connection factory */
   private ManagedConnectionFactory mcf;

   /** The connection listener factory */
   private ConnectionListenerFactory clf;

   /** The default subject */
   private Subject defaultSubject;

   /** The default connection request information */
   private ConnectionRequestInfo defaultCri;

   /** The pool configuration */
   private PoolConfiguration poolConfiguration;

   /** The pool */
   private Pool pool;

   /** 
    * Copy of the maximum size from the pooling parameters.
    * Dynamic changes to this value are not compatible with
    * the semaphore which cannot change be dynamically changed.
    */
   private int maxSize;

   /** The available connection event listeners */
   private ArrayList<ConnectionListener> cls;

   /** The permits used to control who can checkout a connection */
   private Semaphore permits;

   /** The map of connection listeners which has a permit */
   private ConcurrentMap<ConnectionListener, ConnectionListener> clPermits =
      new ConcurrentHashMap<ConnectionListener, ConnectionListener>();

   /** The sub pool */
   private SubPoolContext subPool;

   /** The checked out connections */
   private HashSet<ConnectionListener> checkedOut = new HashSet<ConnectionListener>();

   /** Whether the pool has been started */
   private AtomicBoolean started = new AtomicBoolean(false);

   /** Whether the pool has been shutdown */
   private AtomicBoolean shutdown = new AtomicBoolean(false);

   /** the max connections ever checked out **/
   private volatile int maxUsedConnections = 0;

   /**
    * Constructor
    */
   public SemaphoreArrayListManagedConnectionPool()
   {
   }

   /**
    * {@inheritDoc}
    */
   public void initialize(ManagedConnectionFactory mcf, ConnectionListenerFactory clf, Subject subject,
                          ConnectionRequestInfo cri, PoolConfiguration pc, Pool p, SubPoolContext spc,
                          Logger log)
   {
      this.mcf = mcf;
      this.clf = clf;
      this.defaultSubject = subject;
      this.defaultCri = cri;
      this.poolConfiguration = pc;
      this.maxSize = pc.getMaxSize();
      this.pool = p;
      this.subPool = spc;
      this.log = log;
      this.trace = log.isTraceEnabled();
      this.cls = new ArrayList<ConnectionListener>(this.maxSize);
      this.permits = new Semaphore(this.maxSize, true);
  
      if (pc.isPrefill())
      {
         PoolFiller.fillPool(this);
      }

      reenable();
   }

   /**
    * {@inheritDoc}
    */
   public SubPoolContext getSubPool()
   {
      return subPool;
   }

   /**
    * {@inheritDoc}
    */
   public boolean isRunning()
   {
      return !shutdown.get();
   }

   /**
    * {@inheritDoc}
    */
   public boolean isEmpty()
   {
      synchronized (cls)
      {
         return cls.size() == 0 && checkedOut.size() == 0;
      }
   }

   /**
    * {@inheritDoc}
    */
   public void reenable()
   {
      if (poolConfiguration.getIdleTimeout() != 0L)
      {
         //Register removal support
         IdleRemover.registerPool(this, poolConfiguration.getIdleTimeout());
      }
      
      if (poolConfiguration.getBackgroundValidationInterval() > 0)
      {
         log.debug("Registering for background validation at interval " + 
                   poolConfiguration.getBackgroundValidationInterval());
         
         //Register validation
         ConnectionValidator.registerPool(this, poolConfiguration.getBackgroundValidationInterval());
      }

      shutdown.set(false);
   }

   /**
    * {@inheritDoc}
    */
   public ConnectionListener getConnection(Subject subject, ConnectionRequestInfo cri) throws ResourceException
   {
      subject = (subject == null) ? defaultSubject : subject;
      cri = (cri == null) ? defaultCri : cri;
      long startWait = System.currentTimeMillis();
      try
      {
         if (permits.tryAcquire(poolConfiguration.getBlockingTimeout(), TimeUnit.MILLISECONDS))
         {
            //We have a permit to get a connection. Is there one in the pool already?
            ConnectionListener cl = null;
            do
            {
               synchronized (cls)
               {
                  if (shutdown.get())
                  {
                     permits.release();
                     throw new RetryableUnavailableException("The pool has been shutdown");
                  }

                  int clsSize = cls.size();
                  if (clsSize > 0)
                  {
                     cl = cls.remove(clsSize - 1);
                     checkedOut.add(cl);
                     int size = maxSize - permits.availablePermits();
                     if (size > maxUsedConnections)
                        maxUsedConnections = size;
                  }
               }
               if (cl != null)
               {
                  //Yes, we retrieved a ManagedConnection from the pool. Does it match?
                  try
                  {
                     Object matchedMC = mcf.matchManagedConnections(Collections.singleton(cl.getManagedConnection()),
                                                                    subject, cri);

                     if (matchedMC != null)
                     {
                        if (trace)
                           log.trace("supplying ManagedConnection from pool: " + cl);

                        clPermits.put(cl, cl);

                        return cl;
                     }

                     // Match did not succeed but no exception was thrown.
                     // Either we have the matching strategy wrong or the
                     // connection died while being checked.  We need to
                     // distinguish these cases, but for now we always
                     // destroy the connection.
                     log.warn("Destroying connection that could not be successfully matched: " + cl);

                     synchronized (cls)
                     {
                        checkedOut.remove(cl);
                     }

                     doDestroy(cl);
                     cl = null;
                  }
                  catch (Throwable t)
                  {
                     log.warn("Throwable while trying to match ManagedConnection, destroying connection: " + cl, t);

                     synchronized (cls)
                     {
                        checkedOut.remove(cl);
                     }

                     doDestroy(cl);
                     cl = null;
                  }

                  // We made it here, something went wrong and we should validate 
                  // if we should continue attempting to acquire a connection
                  if (poolConfiguration.isUseFastFail())
                  {
                     log.trace("Fast failing for connection attempt. No more attempts will be made to " +
                               "acquire connection from pool and a new connection will be created immeadiately");
                     break;
                  }
               
               }
            }
            while (cls.size() > 0);

            // OK, we couldnt find a working connection from the pool.  Make a new one.
            try
            {
               // No, the pool was empty, so we have to make a new one.
               cl = createConnectionEventListener(subject, cri);

               synchronized (cls)
               {
                  checkedOut.add(cl);
                  int size = maxSize - permits.availablePermits();
                  if (size > maxUsedConnections)
                     maxUsedConnections = size;
               }

               if (!started.getAndSet(true))
               {
                  if (poolConfiguration.getMinSize() > 0)
                     PoolFiller.fillPool(this);
               }

               if (trace)
                  log.trace("supplying new ManagedConnection: " + cl);

               clPermits.put(cl, cl);

               return cl;
            }
            catch (Throwable t)
            {
               log.warn("Throwable while attempting to get a new connection: " + cl, t);

               // Return permit and rethrow
               synchronized (cls)
               {
                  checkedOut.remove(cl);
               }

               permits.release();

               JBossResourceException.rethrowAsResourceException("Unexpected throwable while trying to " +
                                                                 "create a connection: " + cl, t);
               throw new UnreachableStatementException();
            }
         }
         else
         {
            // We timed out
            throw new ResourceException("No ManagedConnections available within configured blocking timeout ( "
                  + poolConfiguration.getBlockingTimeout() + " [ms] )");
         }

      }
      catch (InterruptedException ie)
      {
         long end = System.currentTimeMillis() - startWait;
         throw new ResourceException("Interrupted while requesting permit! Waited " + end + " ms");
      }
   }

   /**
    * {@inheritDoc}
    */
   public void returnConnection(ConnectionListener cl, boolean kill)
   {
      synchronized (cls)
      {
         if (cl.getState() == ConnectionState.DESTROYED)
         {
            if (trace)
               log.trace("ManagedConnection is being returned after it was destroyed" + cl);

            if (clPermits.containsKey(cl))
            {
               clPermits.remove(cl);
               permits.release();
            }

            return;
         }
      }

      if (trace)
         log.trace("putting ManagedConnection back into pool kill=" + kill + " cl=" + cl);

      try
      {
         cl.getManagedConnection().cleanup();
      }
      catch (ResourceException re)
      {
         log.warn("ResourceException cleaning up ManagedConnection: " + cl, re);
         kill = true;
      }

      synchronized (cls)
      {
         // We need to destroy this one
         if (cl.getState() == ConnectionState.DESTROY || cl.getState() == ConnectionState.DESTROYED)
            kill = true;

         checkedOut.remove(cl);

         // This is really an error
         if (!kill && cls.size() >= poolConfiguration.getMaxSize())
         {
            log.warn("Destroying returned connection, maximum pool size exceeded " + cl);
            kill = true;
         }

         // If we are destroying, check the connection is not in the pool
         if (kill)
         {
            // Adrian Brock: A resource adapter can asynchronously notify us that
            // a connection error occurred.
            // This could happen while the connection is not checked out.
            // e.g. JMS can do this via an ExceptionListener on the connection.
            // I have twice had to reinstate this line of code, PLEASE DO NOT REMOVE IT!
            cls.remove(cl);
         }
         // return to the pool
         else
         {
            cl.used();
            if (!cls.contains(cl))
            {
               cls.add(cl);
            }
            else
            {
               log.warn("Attempt to return connection twice (ignored): " + cl, new Throwable("STACKTRACE"));
            }
         }

         if (clPermits.containsKey(cl))
         {
            clPermits.remove(cl);
            permits.release();
         }
      }

      if (kill)
      {
         if (trace)
            log.trace("Destroying returned connection " + cl);

         doDestroy(cl);
      }
   }

   /**
    * {@inheritDoc}
    */
   public void flush()
   {
      ArrayList<ConnectionListener> destroy = null;

      synchronized (cls)
      {
         if (trace)
            log.trace("Flushing pool checkedOut=" + checkedOut + " inPool=" + cls);

         // Mark checked out connections as requiring destruction
         for (Iterator<ConnectionListener> i = checkedOut.iterator(); i.hasNext();)
         {
            ConnectionListener cl = i.next();

            if (trace)
               log.trace("Flush marking checked out connection for destruction " + cl);

            cl.setState(ConnectionState.DESTROY);
         }

         // Destroy connections in the pool
         while (cls.size() > 0)
         {
            ConnectionListener cl = cls.remove(0);

            if (destroy == null)
               destroy = new ArrayList<ConnectionListener>(1);

            destroy.add(cl);
         }
      }

      // We need to destroy some connections
      if (destroy != null)
      {
         for (int i = 0; i < destroy.size(); ++i)
         {
            ConnectionListener cl = destroy.get(i);

            if (trace)
               log.trace("Destroying flushed connection " + cl);

            doDestroy(cl);
         }

         // We destroyed something, check the minimum.
         if (!shutdown.get() && poolConfiguration.getMinSize() > 0)
            PoolFiller.fillPool(this);
      }
   }

   /**
    * {@inheritDoc}
    */
   public void removeIdleConnections()
   {
      ArrayList<ConnectionListener> destroy = null;
      long timeout = System.currentTimeMillis() - poolConfiguration.getIdleTimeout();

      while (true)
      {
         synchronized (cls)
         {
            // Nothing left to destroy
            if (cls.size() == 0)
               break;

            // Check the first in the list
            ConnectionListener cl = cls.get(0);
            if (cl.isTimedOut(timeout) && shouldRemove())
            {
               // We need to destroy this one
               cls.remove(0);

               if (destroy == null)
                  destroy = new ArrayList<ConnectionListener>(1);

               destroy.add(cl);
            }
            else
            {
               // They were inserted chronologically, so if this one isn't timed out, following ones won't be either.
               break;
            }
         }
      }

      // We found some connections to destroy
      if (destroy != null)
      {
         for (int i = 0; i < destroy.size(); ++i)
         {
            ConnectionListener cl = destroy.get(i);

            if (trace)
               log.trace("Destroying timedout connection " + cl);

            doDestroy(cl);
         }

         // We destroyed something, check the minimum.
         if (!shutdown.get() && poolConfiguration.getMinSize() > 0)
            PoolFiller.fillPool(this);

         // Empty sub-pool
         if (pool != null)
            pool.emptySubPool(this);
      }
   }

   /**
    * {@inheritDoc}
    */
   public void shutdown()
   {
      shutdown.set(true);
      IdleRemover.unregisterPool(this);
      ConnectionValidator.unregisterPool(this);
      flush();
   }

   /**
    * {@inheritDoc}
    */
   public void fillToMin()
   {
      while (true)
      {
         // Get a permit - avoids a race when the pool is nearly full
         // Also avoids unnessary fill checking when all connections are checked out
         try
         {
            if (permits.tryAcquire(poolConfiguration.getBlockingTimeout(), TimeUnit.MILLISECONDS))
            {
               try
               {
                  if (shutdown.get())
                     return;

                  // We already have enough connections
                  if (poolConfiguration.getMinSize() - (cls.size() + checkedOut.size()) <= 0)
                     return;

                  // Create a connection to fill the pool
                  try
                  {
                     ConnectionListener cl = createConnectionEventListener(defaultSubject, defaultCri);

                     synchronized (cls)
                     {
                        if (trace)
                           log.trace("Filling pool cl=" + cl);

                        cls.add(cl);
                     }
                  }
                  catch (ResourceException re)
                  {
                     log.warn("Unable to fill pool ", re);
                     return;
                  }
               }
               finally
               {
                  permits.release();
               }
            }
         }
         catch (InterruptedException ignored)
         {
            log.trace("Interrupted while requesting permit in fillToMin");
         }
      }
   }

   /**
    * Create a connection event listener
    *
    * @param subject the subject
    * @param cri the connection request information
    * @return the new listener
    * @throws ResourceException for any error
    */
   private ConnectionListener createConnectionEventListener(Subject subject, ConnectionRequestInfo cri)
      throws ResourceException
   {
      ManagedConnection mc = mcf.createManagedConnection(subject, cri);

      try
      {
         return clf.createConnectionListener(mc, this);
      }
      catch (ResourceException re)
      {
         mc.destroy();
         throw re;
      }
   }

   /**
    * Destroy a connection
    *
    * @param cl the connection to destroy
    */
   private void doDestroy(ConnectionListener cl)
   {
      if (cl.getState() == ConnectionState.DESTROYED)
      {
         if (trace)
            log.trace("ManagedConnection is already destroyed " + cl);

         return;
      }

      cl.setState(ConnectionState.DESTROYED);

      try
      {
         cl.getManagedConnection().destroy();
      }
      catch (Throwable t)
      {
         log.debug("Exception destroying ManagedConnection " + cl, t);
      }
   }

   /**
    * Should any connections be removed from the pool
    * @return True if connections should be removed; otherwise false
    */
   private boolean shouldRemove()
   {      
      boolean remove = true;
      
      if (poolConfiguration.isStrictMin())
      {
         remove = cls.size() > poolConfiguration.getMinSize();

         if (trace)
            log.trace("StrictMin is active. Current connection will be removed is " + remove);
      }
      
      return remove;
   }
   
   /**
    * {@inheritDoc}
    */
   public void validateConnections() throws Exception
   {

      if (trace)
         log.trace("Attempting to  validate connections for pool " + this);

      if (permits.tryAcquire(poolConfiguration.getBlockingTimeout(), TimeUnit.MILLISECONDS))
      {
         boolean anyDestroyed = false;

         try
         {
            while (true)
            {
               ConnectionListener cl = null;
               boolean destroyed = false;

               synchronized (cls)
               {
                  if (cls.size() == 0)
                  {
                     break;
                  }

                  cl = removeForFrequencyCheck();
               }

               if (cl == null)
               {
                  break;
               }

               try
               {
                  Set candidateSet = Collections.singleton(cl.getManagedConnection());

                  if (mcf instanceof ValidatingManagedConnectionFactory)
                  {
                     ValidatingManagedConnectionFactory vcf = (ValidatingManagedConnectionFactory) mcf;
                     candidateSet = vcf.getInvalidConnections(candidateSet);

                     if (candidateSet != null && candidateSet.size() > 0)
                     {
                        if (cl.getState() != ConnectionState.DESTROY)
                        {
                           doDestroy(cl);
                           destroyed = true;
                           anyDestroyed = true;
                        }
                     }
                  }
                  else
                  {
                     log.warn("Warning: background validation was specified with a non " +
                              "compliant ManagedConnectionFactory interface.");
                  }
               }
               finally
               {
                  if (!destroyed)
                  {
                     synchronized (cls)
                     {
                        returnForFrequencyCheck(cl);
                     }
                  }
               }
            }
         }
         finally
         {
            permits.release();

            if (anyDestroyed && !shutdown.get() && poolConfiguration.getMinSize() > 0)
            {
               PoolFiller.fillPool(this);
            }
         }
      }
   }

   /**
    * Returns the connection listener that should be removed due to background validation
    * @return The listener; otherwise null if none should be removed
    */
   private ConnectionListener removeForFrequencyCheck()
   {
      log.debug("Checking for connection within frequency");

      ConnectionListener cl = null;

      for (Iterator<ConnectionListener> iter = cls.iterator(); iter.hasNext();)
      {
         cl = iter.next();
         long lastCheck = cl.getLastValidatedTime();

         if ((System.currentTimeMillis() - lastCheck) >= poolConfiguration.getBackgroundValidationInterval())
         {
            cls.remove(cl);
            break;
         }
         else
         {
            cl = null;
         }
      }

      return cl;
   }

   /**
    * Return a connection listener to the pool and update its validation timestamp
    * @param cl The listener
    */
   private void returnForFrequencyCheck(ConnectionListener cl)
   {
      log.debug("Returning for connection within frequency");

      cl.setLastValidatedTime(System.currentTimeMillis());
      cls.add(cl);
   }
}