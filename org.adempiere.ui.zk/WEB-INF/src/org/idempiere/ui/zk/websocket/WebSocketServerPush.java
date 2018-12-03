/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - hengsin                         								   *
 **********************************************************************/
package org.idempiere.ui.zk.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zkoss.zk.au.out.AuScript;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.DesktopUnavailableException;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.impl.ExecutionCarryOver;
import org.zkoss.zk.ui.sys.Scheduler;
import org.zkoss.zk.ui.sys.ServerPush;
import org.zkoss.zk.ui.util.Clients;

/**
 * 
 * @author hengsin
 *
 */
public class WebSocketServerPush implements ServerPush {

    private static final String ON_ACTIVATE_DESKTOP = "onActivateDesktop";

    private final AtomicReference<Desktop> desktop = new AtomicReference<Desktop>();

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    
    private ThreadInfo _active;
    private ExecutionCarryOver _carryOver;
    private final Object _mutex = new Object();
    
    private final static Map<String, ServerPushEndPoint> endPointMap = new ConcurrentHashMap<>();
    private List<Schedule<Event>> schedules = new ArrayList<>();

    public WebSocketServerPush() {
    }

    @Override
    public boolean activate(long timeout) throws InterruptedException, DesktopUnavailableException {
    	final Thread curr = Thread.currentThread();
    	if (_active != null && _active.thread.equals(curr)) { //re-activate
			++_active.nActive;
			return true;
		}
    	
		final ThreadInfo info = new ThreadInfo(curr);

		EventListener<Event> task = new EventListener<Event>() {
			@Override
			public void onEvent(Event event) throws Exception {
				if (event.getName().equals(ON_ACTIVATE_DESKTOP))
				{
					synchronized (_mutex) {
						_carryOver = new ExecutionCarryOver(desktop.get());
						
						synchronized (info) {
							info.nActive = 1; //granted
							info.notifyAll();
						}
						
						try {
							_mutex.wait(); //wait until the server push is done
						} catch (InterruptedException ex) {
							throw UiException.Aide.wrap(ex);
						}
					}
				}
			}
		};
		
		synchronized (info) {
			Executions.schedule(desktop.get(), task, new Event(ON_ACTIVATE_DESKTOP));
			if (info.nActive == 0)
				info.wait(timeout <= 0 ? 10*60*1000: timeout);
		}
    	
    	_carryOver.carryOver();
    	_active = info;
    	
    	return true;
    }

    private boolean echo() {
    	Desktop dt = desktop.get();
        if (dt != null) {
        	ServerPushEndPoint endPoint = getEndPoint(dt.getId());
        	if (endPoint == null) {
        		if (dt.isServerPushEnabled()) {
        			try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
					}
        			endPoint = getEndPoint(dt.getId());
        		}
        	}
        	if (endPoint != null) {
        		endPoint.echo();
        		return true;
        	}
        } 
        return false;
    }

    @Override
    public boolean deactivate(boolean stop) {
    	boolean stopped = false;
		if (_active != null && Thread.currentThread().equals(_active.thread)) {
			if (--_active.nActive <= 0) {
				if (stop)
				{
					stop();
					stopped = true;
				}

				_carryOver.cleanup();
				_carryOver = null;
				_active.nActive = 0; //just in case
				_active = null;
								
				synchronized (_mutex) {
					_mutex.notifyAll();
				}
			}
		}
		return stopped;
    }

    @Override
    public boolean isActive() {
    	return _active != null && _active.nActive > 0;
    }

    @SuppressWarnings("unchecked")
	@Override
    public void onPiggyback() {
    	Schedule<Event>[] pendings = null;
    	synchronized (schedules) {
    		if (!schedules.isEmpty()) {
    			pendings = schedules.toArray(new Schedule[0]);
    			schedules = new ArrayList<>();
    		}
    	}
    	if (pendings != null && pendings.length > 0) {
    		for(Schedule<Event> p : pendings) {
    			p.scheduler.schedule(p.task, p.event);
    		}
    	}
    	
    	//check web socket end point 
    	Desktop dt = desktop.get(); 
    	if (dt != null) {
        	ServerPushEndPoint endPoint = getEndPoint(dt.getId());
        	if (endPoint == null) {
        		if (dt.isServerPushEnabled()) {
        			startServerPushAtClient(dt);
        		}
        	}
    	}    	
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public <T extends Event> void schedule(EventListener<T> task, T event,
			Scheduler<T> scheduler) {
    	if (Executions.getCurrent() == null) {
    		//save for schedule at on piggyback event
	        synchronized (schedules) {
				schedules.add(new Schedule(task, event, scheduler));
			}
	        echo();
    	} else {
    		//in event listener thread, can schedule immediately
    		scheduler.schedule(task, event);
    	}
    }

    private class Schedule<T extends Event> {
    	private EventListener<T> task;
		private T event;
		private Scheduler<T> scheduler;

		private Schedule(EventListener<T> task, T event, Scheduler<T> scheduler) {
    		this.task = task;
    		this.event = event;
    		this.scheduler = scheduler;
    	}
    }
    
    @Override
    public void start(Desktop desktop) {
        Desktop oldDesktop = this.desktop.getAndSet(desktop);
        if (oldDesktop != null) {
            log.warn("Server push already started for desktop " + desktop.getId());
            return;
        }

        log.debug("Starting server push for " + desktop);
        startServerPushAtClient(desktop);
    }

	private void startServerPushAtClient(Desktop desktop) {
		Clients.response("org.idempiere.websocket.serverpush.start", new AuScript(null, "org.idempiere.websocket.startServerPush('" + desktop.getId() + "');"));
	}

    @Override
    public void stop() {
        Desktop desktop = this.desktop.getAndSet(null);
        if (desktop == null) {
            log.warn("Server push hasn't been started or has already stopped");
            return;
        }

        log.debug("Stopping server push for " + desktop);
        Clients.response("org.idempiere.websocket.serverpush.stop", new AuScript(null, "org.idempiere.websocket.stopServerPush('" + desktop.getId() + "');"));
    }

	private static class ThreadInfo {
		private final Thread thread;
		/** # of activate() was called. */
		private int nActive;
		private ThreadInfo(Thread thread) {
			this.thread = thread;
		}
		public String toString() {
			return "[" + thread + ',' + nActive + ']';
		}
	}

	@Override
	public void resume() {
		if (this.desktop.get() != null) {
			Desktop desktop = this.desktop.getAndSet(null);
			start(desktop);
		}
	}
	
	/**
	 * Register web socket end point for desktop
	 * @param dtid Desktop id
	 * @param endpoint Connected web socket end point
	 */
	public static void registerEndPoint(String dtid, ServerPushEndPoint endpoint) {
		endPointMap.put(dtid, endpoint);
	}
	
	/**
	 * Remove web socket end point for desktop
	 * @param dtid Desktop id
	 * @return true if there's end point register previously for desktop, false otherwise
	 */
	public static boolean unregisterEndPoint(String dtid) {
		ServerPushEndPoint endpoint = endPointMap.remove(dtid);
		return endpoint != null;
	}
	
	/**
	 * Get web socket end point for desktop
	 * @param dtid Desktop id
	 * @return Web socket end point
	 */
	public static ServerPushEndPoint getEndPoint(String dtid) {
		ServerPushEndPoint endpoint = endPointMap.get(dtid);
		return endpoint;
	}
}