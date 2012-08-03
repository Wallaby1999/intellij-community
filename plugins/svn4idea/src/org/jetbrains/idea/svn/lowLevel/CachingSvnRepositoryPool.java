/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.lowLevel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Pair;
import com.intellij.util.Processor;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.ThrowableConvertor;
import com.intellij.util.containers.hash.HashSet;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;

import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: Irina.Chernushina
 * Date: 7/30/12
 * Time: 3:39 PM
 */
public class CachingSvnRepositoryPool implements SvnRepositoryPool {
  private static final long DEFAULT_IDLE_TIMEOUT = 60*1000;

  private static final int ourMaxCachedDefault = 5;
  private static final int ourMaxConcurrentDefault = 20;
  static final int ourMaxTotal = 100;

  private final int myMaxCached;      // per host
  private int myMaxConcurrent;        // per host
  private final ThrowableConvertor<SVNURL, SVNRepository, SVNException> myCreator;
  private final ThrowableConsumer<Pair<SVNURL, SVNRepository>, SVNException> myAdjuster;
  private final Processor<Thread> myCancelChecker;
  private final Map<String, RepoGroup> myGroups;
  private ApplicationLevelNumberConnectionsGuard myGuard;

  private boolean myDisposed;
  private final Object myLock;

  public CachingSvnRepositoryPool(ThrowableConvertor<SVNURL, SVNRepository, SVNException> creator,
                                  final int maxCached, final int maxConcurrent,
                                  ThrowableConsumer<Pair<SVNURL, SVNRepository>, SVNException> adjuster,
                                  final Processor<Thread> cancelChecker, final ApplicationLevelNumberConnectionsGuard guard) {
    myGuard = guard;
    myLock = new Object();
    myCreator = creator;
    myAdjuster = adjuster;
    myCancelChecker = cancelChecker;
    myMaxCached = maxCached > 0 ? maxCached : ourMaxCachedDefault;
    myMaxConcurrent = maxConcurrent > 0 ? maxConcurrent : ourMaxConcurrentDefault;
    if (myMaxConcurrent < myMaxCached) {
      myMaxConcurrent = myMaxCached;
    }
    myGroups = new HashMap<String, RepoGroup>();
    myDisposed = false;
  }

  public CachingSvnRepositoryPool(ThrowableConvertor<SVNURL, SVNRepository, SVNException> creator, ThrowableConsumer<Pair<SVNURL, SVNRepository>, SVNException> adjuster,
                                  final Processor<Thread> cancelChecker, final ApplicationLevelNumberConnectionsGuard guard) {
    this(creator, -1, -1, adjuster, cancelChecker, guard);
  }

  public void waitingInterrupted() {
    synchronized (myLock) {
      for (RepoGroup group : myGroups.values()) {
        group.interruptWaiting();
      }
    }
  }

  public void check() {
    synchronized (myLock) {
      if (myDisposed) return;
      for (RepoGroup group : myGroups.values()) {
        group.recheck();
      }
    }
  }

  @Override
  public SVNRepository getRepo(SVNURL url, boolean mayReuse) throws SVNException {
    synchronized (myLock) {
      if (myDisposed) throw new ProcessCanceledException();

      final String host = url.getHost();
      RepoGroup group = myGroups.get(host);
      if (group == null) {
        group = new RepoGroup(myCreator, myMaxCached, myMaxConcurrent, myAdjuster, myGuard, myCancelChecker, myLock);
      }
      myGroups.put(host, group);
      return group.getRepo(url, mayReuse);
    }
  }

  @Override
  public void returnRepo(SVNRepository repo) {
    synchronized (myLock) {
      if (myDisposed) {
        repo.closeSession();
        return;
      }
      final String host = repo.getLocation().getHost();
      RepoGroup group = myGroups.get(host);
      assert group != null;
      group.returnRepo(repo);
    }
  }

  @Override
  public void dispose() {
    synchronized (myLock) {
      myDisposed = true;
      for (RepoGroup group : myGroups.values()) {
        group.dispose();
      }
    }
  }

  public int getNumberInactiveConnections() {
    synchronized (myLock) {
      int result = 0;
      for (RepoGroup group : myGroups.values()) {
        result += group.getInactiveSize();
      }
      return result;
    }
  }

  public void closeInactive() {
    synchronized (myLock) {
      for (RepoGroup group : myGroups.values()) {
        group.closeInactive();
      }
    }
  }

  // per host
  public static class RepoGroup implements SvnRepositoryPool {
    private final ThrowableConvertor<SVNURL, SVNRepository, SVNException> myCreator;

    private final int myMaxCached;      // per host
    private final int myMaxConcurrent;        // per host
    private final ThrowableConsumer<Pair<SVNURL, SVNRepository>, SVNException> myAdjuster;
    private final ApplicationLevelNumberConnectionsGuard myGuard;
    private final Processor<Thread> myCancelChecker;

    private final TreeMap<Long, SVNRepository> myInactive;
    private final Set<SVNRepository> myUsed;
    private boolean myDisposed;
    private final Object myWait;

    private RepoGroup(ThrowableConvertor<SVNURL, SVNRepository, SVNException> creator, int cached, int concurrent,
                      final ThrowableConsumer<Pair<SVNURL, SVNRepository>, SVNException> adjuster,
                      final ApplicationLevelNumberConnectionsGuard guard, final Processor<Thread> cancelChecker, final Object waitObj) {
      myCreator = creator;
      myMaxCached = cached;
      myMaxConcurrent = concurrent;
      myAdjuster = adjuster;
      myGuard = guard;
      myCancelChecker = cancelChecker;

      myInactive = new TreeMap<Long, SVNRepository>();
      myUsed = new HashSet<SVNRepository>();

      myDisposed = false;
      myWait = waitObj;
    }

    public void dispose() {
      for (SVNRepository repository : myInactive.values()) {
        repository.closeSession();
      }
      myInactive.clear();
      for (SVNRepository repository : myUsed) {
        repository.closeSession();
      }
      myUsed.clear();

      synchronized (myWait) {
        myWait.notifyAll();
      }
      myDisposed = true;
      myWait.notifyAll();
    }

    public void interruptWaiting() {
      synchronized (myWait) {
        myWait.notifyAll();
      }
    }

    @Override
    public SVNRepository getRepo(SVNURL url, boolean mayReuse) throws SVNException {
      if (myDisposed) return null;
      if (! myInactive.isEmpty() && mayReuse) {
        return fromInactive(url);
      }
      myGuard.waitForTotalNumberOfConnectionsOk(myCancelChecker);

      if (myUsed.size() >= myMaxConcurrent) {
        synchronized (myWait) {
          if ((myUsed.size() + myInactive.size()) >= myMaxConcurrent) {
            while ((myUsed.size() + myInactive.size()) >= myMaxConcurrent && ! myDisposed) {
              try {
                myWait.wait(500);
              }
              catch (InterruptedException e) {
                //
              }
              if (! myCancelChecker.process(Thread.currentThread())) {
                myWait.notifyAll();    // unblock others
                throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CANCELLED));
              }
            }

            //somewhy unblocked
            if (myDisposed) {
              myWait.notifyAll();    // unblock others
              throw new ProcessCanceledException();
            }
          }
        }
      }
      if (! myInactive.isEmpty() && mayReuse) {
        return fromInactive(url);
      }
      assert myUsed.size() <= myMaxConcurrent;
      final SVNRepository fun = myCreator.convert(url);
      myUsed.add(fun);
      return fun;
    }

    private SVNRepository fromInactive(SVNURL url) throws SVNException {
      // oldest
      final Map.Entry<Long, SVNRepository> entry = myInactive.firstEntry();
      final SVNRepository next = entry.getValue();
      myInactive.remove(entry.getKey());
      myAdjuster.consume(Pair.create(url, next));
      if (! myGuard.shouldKeepConnectionLocally(myCancelChecker)) {
        myInactive.clear();
      }
      return next;
    }

    @Override
    public void returnRepo(SVNRepository repo) {
      myUsed.remove(repo);
      if (myGuard.shouldKeepConnectionLocally(myCancelChecker) && myInactive.size() < myMaxCached) {
        long time = System.currentTimeMillis();
        if (myInactive.containsKey(time)) {
          time = myInactive.lastKey() + 1;
        }
        myInactive.put(time, repo);
      }
    }

    public void recheck() {
      final long time = System.currentTimeMillis();
      final Set<Long> longs = myInactive.keySet();
      final Iterator<Long> iterator = longs.iterator();
      while (iterator.hasNext()) {
        final Long next = iterator.next();
        if (time - next > DEFAULT_IDLE_TIMEOUT) {
          myInactive.get(next).closeSession();
          iterator.remove();
        } else {
          break;
        }
      }
    }

    public int closeInactive() {
      int cnt = myInactive.size();
      for (SVNRepository repository : myInactive.values()) {
        repository.closeSession();
      }
      myInactive.clear();
      return cnt;
    }

    public int getUsedSize() {
      return myUsed.size();
    }

    public int getInactiveSize() {
      return myInactive.size();
    }
  }

  Processor<Thread> getCancelChecker() {
    return myCancelChecker;
  }

  public Map<String, RepoGroup> getGroups() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    return myGroups;
  }
}
