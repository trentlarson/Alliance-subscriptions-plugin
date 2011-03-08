package info.familyhistories.alliancep2p;

import info.familyhistories.alliancep2p.FriendFileChangeDetectorPlugIn.FriendSubscription;
import info.familyhistories.alliancep2p.FriendFileChangeDetectorPlugIn.FriendSubscriptionPersistence;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.alliance.core.CoreSubsystem;

/**
 * Use a file to store the array of subscribed folders.
 * 
 * You can remove the file to erase all the settings, and it'll be recreated blank.
 */
public class SerializedFriendSubscriptions implements FriendSubscriptionPersistence {
	
	private final String filename;
	
	private List<FriendSubscription> friendSubList = new ArrayList<FriendSubscription>();
	
	SerializedFriendSubscriptions(CoreSubsystem core) {
		String downloadFolder = core.getSettings().getInternal().getDownloadfolder();
		String settingsFolder = downloadFolder.substring(0, downloadFolder.lastIndexOf(File.separator) + 1);
		filename = settingsFolder + File.separator + "friendFileChangeDetector.ser";
		
		if (new File(filename).exists()) {
			load();
		}
	}
	
	public void shutdown() {
		save();
	}
	
	private void save() {
		try {
			new ObjectOutputStream(new FileOutputStream(filename)).writeObject(friendSubList);
		} catch (IOException e) {
			System.err.println("Due to the following error, we could not save the friend subscription settings.");
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("unchecked")
	private void load() {
		try {
			friendSubList = 
				(List<FriendSubscription>)
				new ObjectInputStream(new FileInputStream(filename)).readObject();
		} catch (IOException e) {
			System.err.println("Due to the following error, we could not load the friend subscription settings.");
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			System.err.println("Due to the following error, we could not load the friend subscription settings.");
			e.printStackTrace();
		}
	}
	
	public boolean createFriendSubscription(FriendSubscription fsub) {
		friendSubList.add(fsub);
		return true;
	}
	
	public List<FriendSubscription> getFriendSubscriptions() {
		return friendSubList;
	}
	
	public List<FriendSubscription> getFriendSubscriptions(Integer guid) {
		List<FriendSubscription> forFriend = new ArrayList<FriendSubscription>();
		for (FriendSubscription fsub : friendSubList) {
			if (fsub.guid == guid) {
				forFriend.add(fsub);
			}
		}
		return forFriend;
	}
	
	public FriendSubscription getFriendSubscription(Integer guid, String shareBasePath, String shareSubPath) {
		for (FriendSubscription fsub : friendSubList) {
			if (fsub.guid == guid
				&& fsub.shareBasePath.equals(shareBasePath)
				&& fsub.shareSubPath.equals(shareSubPath)) {
				return fsub;
			}
		}
		return null;
	}
	
	public boolean updateFriendSubscription(Integer friendGuid, String shareBasePath, String shareSubPath, long lastModifiedTime) throws SQLException {
		FriendSubscription fsub = getFriendSubscription(friendGuid, shareBasePath, shareSubPath);
		if (fsub != null) {
			friendSubList.remove(fsub);
			friendSubList.add(new FriendSubscription(friendGuid, shareBasePath, shareSubPath, fsub.localPath, lastModifiedTime));
			return true;
		} else {
			return false;
		}
	}
}
