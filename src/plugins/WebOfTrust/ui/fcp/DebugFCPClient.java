package plugins.WebOfTrust.ui.fcp;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager.IdentitiesSubscription;
import plugins.WebOfTrust.SubscriptionManager.ScoresSubscription;
import plugins.WebOfTrust.SubscriptionManager.TrustsSubscription;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ChangeSet;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.SubscribedObjectChangedHandler;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.SubscriptionSynchronizationHandler;

import com.db4o.ObjectSet;

import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * A FCP client which can connect to WOT itself for debugging:
 * 
 * It is able to validate the data it has received via FCP against the actual data in the WOT database.
 * This serves as an online test for the event-notifications code:
 * - If WOT is run with logging set to {@link LogLevel#DEBUG}, the reference client will be run inside of WOT and connect to it.
 * - It will store ALL {@link Identity}, {@link Trust} and {@link Score} objects received via FCP.
 * - At shutdown, it will compare the final state of what it has received against whats stored in the regular WOT database
 * - If both datasets match, the test has succeeded.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class DebugFCPClient implements FCPClientReferenceImplementation.ConnectionStatusChangedHandler {
	
	private final WebOfTrust mWebOfTrust;
	
	private FCPClientReferenceImplementation mClient;
	
	/**
	 * Stores the {@link Identity} objects which we have received via FCP as part of the {@link IdentitiesSubscription}.
	 * Key = {@link Identity#getID()}.
	 */
	private HashMap<String, Identity> mReceivedIdentities = null; // Cannot be constructed here because the super constructor needs it
	
	/**
	 * Stores the {@link Trust} objects which we have received via FCP as part of the {@link TrustsSubscription}.
	 * Key = {@link Trust#getID()}.
	 */
	private final HashMap<String, Trust> mReceivedTrusts = new HashMap<String, Trust>();
	
	/**
	 * Stores the {@link Score} objects which we have received via FCP as part of the {@link ScoresSubscription}.
	 * Key = {@link Score#getID()}.
	 */
	private final HashMap<String, Score> mReceivedScores = new HashMap<String, Score>();
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#DEBUG} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logDEBUG = false;
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#MINOR} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logMINOR = false;
	
	static {
		// Necessary for automatic setting of logDEBUG and logMINOR
		Logger.registerClass(DebugFCPClient.class);
	}

	
	private DebugFCPClient(final WebOfTrust myWebOfTrust, final Executor myExecutor, Map<String, Identity> identityStorage) {
		mClient = new FCPClientReferenceImplementation(identityStorage, myWebOfTrust.getPluginRespirator(), myExecutor, this);
		mWebOfTrust = myWebOfTrust;
	}
	
	public static DebugFCPClient construct(final WebOfTrust myWebOfTrust) {
		final HashMap<String, Identity> identityStorage = new HashMap<String, Identity>();
		final DebugFCPClient client = new DebugFCPClient(myWebOfTrust, myWebOfTrust.getPluginRespirator().getNode().executor, identityStorage);
		client.mReceivedIdentities = identityStorage;
		return client;
	}
	
	public void start() {
		mClient.start();
		mClient.subscribe(Identity.class, 
				new SubscriptionSynchronizationHandlerImpl<Identity>(Identity.class,
				    mReceivedIdentities),
				new SubscribedObjectChangedHandlerImpl<Identity>(mReceivedIdentities));
		
		mClient.subscribe(Trust.class, 
				new SubscriptionSynchronizationHandlerImpl<Trust>(Trust.class,
				    mReceivedTrusts),
				new SubscribedObjectChangedHandlerImpl<Trust>(mReceivedTrusts));
		
		mClient.subscribe(Score.class, 
				new SubscriptionSynchronizationHandlerImpl<Score>(Score.class,
				    mReceivedScores),
				new SubscribedObjectChangedHandlerImpl<Score>(mReceivedScores));
	}
	
	public void stop() { 
		mClient.stop();
		
		Logger.normal(this, "terminate(): Amending edition hints...");
		// Event-notifications does not propagate edition hints because that would cause a lot of traffic so we need to set them manually
		final ObjectSet<Identity> allIdentities = mWebOfTrust.getAllIdentities();
		for(final Identity identity : allIdentities) {
			final Identity received = mReceivedIdentities.get(identity.getID());
			if(received == null)
				continue;
			
			received.forceSetNewEditionHint(identity.getLatestEditionHint());
		}
		
		Logger.normal(this, "terminate(): Validating received data against WOT database...");
		validateAgainstDatabase(allIdentities, mReceivedIdentities);
		validateAgainstDatabase(mWebOfTrust.getAllTrusts(), mReceivedTrusts);
		validateAgainstDatabase(mWebOfTrust.getAllScores(), mReceivedScores);
		Logger.normal(this, "terminate() finished.");
	}
	
	private <T extends Persistent> void validateAgainstDatabase(final ObjectSet<T> expectedSet, final HashMap<String, T> actualSet) {
		if(actualSet.size() != expectedSet.size())
			Logger.error(this, "Size mismatch for " + actualSet + ": actual size " + actualSet.size() + " != expected size " + expectedSet.size());
		
		for(final T expected : expectedSet) {
			final T actual = actualSet.get(expected.getID());
			if(actual == null || !actual.equals(expected)) {
				Logger.error(this, "Mismatch: actual " + actual + " not equals() to expected " + expected);
				if(actual != null)
					actual.equals(expected); // For being able to step inside of it with the debugger if you set a breakpoint at the previous line.
			}
		}
	}

	@Override
	public void handleConnectionStatusChanged(final boolean connected) {
		if(logMINOR) Logger.minor(this, "handleConnectionStatusChanged(" + connected + ")");
	}

	private final class SubscriptionSynchronizationHandlerImpl<T extends Persistent> implements SubscriptionSynchronizationHandler<T> {
		private final Class<T> mClass;
		private final HashMap<String, T> mTarget;
		
		public SubscriptionSynchronizationHandlerImpl(final Class<T> myClass,
				final HashMap<String, T> myTarget) {
			mClass = myClass;
			mTarget = myTarget;
		}
		
		/**
		 * Fill our existing "database" (the {@link HashMap} mTarget) with the synchronization of ALL data which we have received from WOT.
		 */
		public void handleSubscriptionSynchronization(final Collection<T> source) {
            if(logMINOR) {
                Logger.minor(this, "handleSubscriptionSynchronization() for subscription type: "
                    + mClass);
            }

			if(mTarget.size() > 0) {
				Logger.normal(this, "Received additional synchronization, validating existing data against it...");
				// ATTENTION: This can happen when the connection to WOT is lost temporarily. Therefore, in a real client, you should
				// update your existing dataset WITHOUT complaining about mismatches.

				if(source.size() != mTarget.size())
					Logger.error(this, "Size mismatch: received size " + source.size() + " != existing size " + mTarget.size());
				else {
					for(final T expected : source) {
						final T existing = mTarget.get(expected);
						if(existing == null)
							Logger.error(this, "Not found: expected " + expected);
						else if(!existing.equals(expected)) {
							Logger.error(this, "Not equals: expected " + expected + " to existing " + existing);
							existing.equals(expected); // For being able to step inside of it with the debugger if you set a breakpoint at the previous line.
						}
					}
				}
				mTarget.clear();
			}

			for(final T p : source) {
				mTarget.put(p.getID(), p);
			}

			if(logMINOR) Logger.minor(this, "handleSubscriptionSynchronization() finished.");
		}
	}

	private final class SubscribedObjectChangedHandlerImpl<T extends Persistent> implements SubscribedObjectChangedHandler<T> {
		private final HashMap<String, T> mTarget;
		
		public SubscribedObjectChangedHandlerImpl(final HashMap<String, T> myTarget) {
			mTarget = myTarget;
		}
		
		/**
		 * Update our existing "database" (the {@link HashMap} mTarget) with the changed data which we have received from WOT.
		 * 
		 * It does more than that though: It checks whether the contents of the {@link FCPClientReferenceImplementation.ChangeSet} make sense.
		 * For example our existing data in the HashMap should match the {@link FCPClientReferenceImplementation.ChangeSet#beforeChange}. 
		 */
		public void handleSubscribedObjectChanged(final ChangeSet<T> changeSet) {
			if(logMINOR) Logger.minor(this, "handleSubscribedObjectChanged(): " + changeSet);

			// Check validity of existing data
			if(changeSet.beforeChange != null) {
				final T currentBeforeChange = mTarget.get(changeSet.beforeChange.getID());
				if(!currentBeforeChange.equals(changeSet.beforeChange))
					Logger.error(this, "Existing data is not equals() to changeSet.beforeChange: currentBeforeChange=" + currentBeforeChange + "; changeSet=" + changeSet);

				if(changeSet.afterChange != null && currentBeforeChange.equals(changeSet.afterChange)) {
					if(!changeSet.beforeChange.equals(changeSet.afterChange))
						Logger.warning(this, "Received useless notification, we already have this data: " + changeSet);
					else
						Logger.warning(this, "Received notification which changed nothing: " + changeSet);
				}
			} else {
				if(mTarget.containsKey(changeSet.afterChange.getID()))
					Logger.error(this, "ChangeSet claims to create the object but we already have it: existing="  
							+ mTarget.get(changeSet.afterChange.getID()) + "; changeSet=" + changeSet);
			}

			// Update our "database" HashMap
			if(changeSet.afterChange != null) {
				/* Checked in changeSet already */
				// assert(changeSet.beforeChange.getID(), changeSet.afterChange.getID()); 
				mTarget.put(changeSet.afterChange.getID(), changeSet.afterChange);
			} else
				mTarget.remove(changeSet.beforeChange.getID());

			if(logMINOR) Logger.minor(this, "handleSubscribedObjectChanged finished.");
		}
	}
}
