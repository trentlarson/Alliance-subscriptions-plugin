package info.familyhistories.alliancep2p;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.alliance.core.CoreSubsystem;
import org.alliance.core.NonWindowUICallback;
import org.alliance.core.comm.rpc.GetHashesForPath;
import org.alliance.core.comm.rpc.GetShareBaseList;
import org.alliance.core.comm.rpc.PlugInCommunication;
import org.alliance.core.node.Friend;
import org.alliance.core.node.Node;
import org.alliance.core.plugins.ConsolePlugInExtension;
import org.alliance.core.plugins.PlugIn;
import org.alliance.launchers.console.Console.Printer;

/**
 * 
 * Grab any new files created inside any subscribed folders.
 * 
 * To create a subscription, run something like this in the debug console (see the console extension for details):
 * newfsub 1643718002 alliance-on-trunk subscribe-to-this/ /Users/tlarson/dev/alliance/working-trunk2/working/sub-to-trunk 1295818783
 * 
 * (If you want to reset values, you'll have to go directly to the database or the serialization file; see dbFriendSub setup.)
 * 
 */
public class FriendFileChangeDetectorPlugIn implements PlugIn {

	// Remember: if you change these after some live in the wild, you'll have to handle versioning.
	private static final String COMM_PREFIX = FriendFileChangeDetectorPlugIn.class.getName();
	private static final String COMM_CHANGES_QUERY = ".changes.query";
	private static final String COMM_CHANGES_PREFIX_AND_QUERY = COMM_PREFIX + COMM_CHANGES_QUERY;
	private static final String COMM_CHANGES_REPLY = ".changes.reply";
	private static final String COMM_CHANGES_PREFIX_AND_REPLY = COMM_PREFIX + COMM_CHANGES_REPLY;
	private static final String KEY_SHARE_BASE_INDEX = "shareBaseIndex";
	private static final String KEY_SHARE_SUB_PATH = "shareSubPath";
	private static final String KEY_FILE_LIST = "fileList";
	private static final String KEY_LAST_MODIFIED_TIME = "lastModifiedTime";
	private static final String KEY_LAST_KNOWN_MODIFIED_TIME = "lastKnownModifiedTime";
	
	/**
	 * Map from Friend GUID to list of their sharebases, each in the array at the index of their share base number.
	 * 
	 * We will do all communications with share indexes to avoid the security problems of passing whole paths around.
	 */
	private static final Map<Integer, String[]> shareBasesForFriend = Collections.synchronizedMap(new HashMap<Integer, String[]>());
	
	
	
	
	public static class FriendSubscription implements Serializable {
		private static final long serialVersionUID = -4870889561748390743L;
		
		final int guid;
		final String shareBasePath, shareSubPath, localPath;
		final long lastKnownModified;
		public FriendSubscription(int _guid, String _shareBasePath, String _shareSubPath, String _localPath, long _lastKnownModified) {
			this.guid = _guid;
			this.shareBasePath = _shareBasePath;
			this.shareSubPath = _shareSubPath;
			this.localPath = _localPath;
			this.lastKnownModified = _lastKnownModified;
		}
		public String toString() {
			return "FriendSubscription for " + guid + " in share " + shareBasePath + " sub-dir " + shareSubPath + " into " + localPath + " modified " + lastKnownModified;
		}
	}
	
	public static interface FriendSubscriptionPersistence {
		public boolean createFriendSubscription(FriendSubscription fsub) throws SQLException;
		public List<FriendSubscription> getFriendSubscriptions() throws SQLException;
		public List<FriendSubscription> getFriendSubscriptions(Integer guid) throws SQLException;
		public FriendSubscription getFriendSubscription(Integer guid, String shareBasePath, String shareSubPath) throws SQLException;
		public boolean updateFriendSubscription(Integer friendGuid, String shareBasePath, String shareSubPath, long lastModifiedTime) throws SQLException;
		public void shutdown();
	}
	
	
	
	
    CoreSubsystem core;
    FriendSubscriptionPersistence dbFriendSub;

    @Override
    public void init(CoreSubsystem _core) throws SQLException {
        this.core = _core;
        //this.dbFriendSub = new DatabaseFriendSubscriptions(_core);
        this.dbFriendSub = new SerializedFriendSubscriptions(_core);
        
        
        /**
        // just an example: show a status whenever a friend connects
        core.addUICallback(new NonWindowUICallback() {
        	@Override
        	public void nodeOrSubnodesUpdated(Node node) {
        		System.out.println("The node " + node.getNickname() + " was updated... now to send a message...");
        		if (node instanceof Friend
        			&& node.isConnected()) {
        			core.queNeedsUserInteraction(new PostMessageInteraction("This is a fake message, supposedly from " + node.getNickname(), node.getGuid()));
        		}
        	}
        });
        **/
        
        // when a friend connects, let's ask their share-bases (after which we can ask for subscription changes)
        core.addUICallback(new NonWindowUICallback() {
        	@Override
        	public void nodeOrSubnodesUpdated(Node node) {
        		System.out.println("The node " + node.getNickname() + " was updated.");
        		if (node instanceof Friend
        			&& node.isConnected()
        			&& !shareBasesForFriend.containsKey(node.getGuid())) {
        			Friend friend = (Friend) node;
            		System.out.println("They're a friend, so let's ask for their share bases in preparation for subscription update checks.");
        			try {
        				friend.getFriendConnection().send(new GetShareBaseList());
        			} catch (IOException e) {
                		System.err.println("Problem asking for share-bases from " + friend.getGuid() + ", so we probably won't do any subscription updates.");
        				e.printStackTrace();
        			}
        		}
        	}
        });
        
        // after we receive their share-bases, turn around and ask for changes to subscribed folders
        core.addUICallback(new NonWindowUICallback() {
            @Override
            public void receivedShareBaseList(Friend friend, String[] shareBaseNames) {
                // when we receive a list of our friends share-bases, we need to record the numerical index for later requests
            	shareBasesForFriend.put(friend.getGuid(), shareBaseNames);
            	System.out.println("Received this list of the share-bases for friend " + friend.getGuid() + ": " + Arrays.asList(shareBaseNames));
            	// optimization: remove any FriendSubsciptions with share-bases that are no longer available
            	
            	// now let's check for subscription changes
        		try {
        			List<FriendSubscription> fsubs = dbFriendSub.getFriendSubscriptions(friend.getGuid());
        			for (FriendSubscription fsub : fsubs) {
        				try {
        					int shareBaseIndex = shareBaseIndex(fsub.shareBasePath, shareBasesForFriend.get(friend.getGuid()));
        					if (shareBaseIndex > -1) {
        						sendLastModifiedQuery(friend, shareBaseIndex, fsub.shareSubPath, fsub.lastKnownModified);
        					} else {
        						System.err.println("FriendSubscription share-base '" + fsub.shareBasePath + "' is no longer in share-bases for Friend " + friend.getGuid() + ".");
        					}
        				} catch (IOException e) {
                			System.err.println("Failed to send modification request.");
                			e.printStackTrace();
        				}
        			}
        		} catch (SQLException e) {
        			System.err.println("Failed to get the friend subscription info.");
        			e.printStackTrace();
        		}
            }
        });
        
        // add listener for subscription-change replies, and reply with the list of files to download
        core.addUICallback(new NonWindowUICallback() {
        	@Override
        	public void pluginCommunicationReceived(Friend source, String data) {
            	System.out.println("Received plugin comm from " + source + ": " + data);
        		if (data.startsWith(COMM_CHANGES_PREFIX_AND_QUERY)) {
        			String paramString = data.substring(COMM_CHANGES_PREFIX_AND_QUERY.length());
        			// strip off the "=" and the curly braces
        			String allKeyVals = paramString.substring(2, paramString.length() - 1);
        			// parse out each "key":"value"
        			String[] keyVals = allKeyVals.split(",");
        			// retrieve the values
        			int shareBaseIndex = -1;
        			String shareSubPath = null;
        			long lastKnownModifiedTime = -1;
        			for (String keyVal : keyVals) {
        				String[] elems = keyVal.split(":");
        				// strip the quotes off the key names
        				String key = elems[0].substring(1, elems[0].length()-1);
        				if (key.equals(KEY_SHARE_BASE_INDEX)) {
        					shareBaseIndex = new Integer(elems[1]).intValue();
        				} else if (key.equals(KEY_SHARE_SUB_PATH)) {
        					shareSubPath = elems[1].substring(1, elems[1].length()-1);
        				} else if (key.equals(KEY_LAST_KNOWN_MODIFIED_TIME)) {
        					lastKnownModifiedTime = new Long(elems[1]).longValue();
        				} else {
        					System.err.println("Got some unknown data for plugin " + COMM_CHANGES_PREFIX_AND_QUERY + ".  Expected JSON with keys for shareBaseIndex & pathTofile, but got this key: " + key);
        				}
        			}
                    if (shareBaseIndex == -1
                    	|| shareSubPath == null
                    	|| lastKnownModifiedTime == -1) {
                        System.err.println("Got some bad data for plugin " + COMM_CHANGES_PREFIX_AND_QUERY + ".  Expected JSON with keys for share-base index & sub-path & modified time, but got this: " + paramString);
                    } else {
                    	System.out.println("Will get times for shareBaseIndex " + shareBaseIndex + " & path " + shareSubPath);
                    	try {
                    		String shareBasePath = core.getFileManager().getShareManager().getBaseByIndex(shareBaseIndex).getPath();
                    		String subscribedPath = shareBasePath + File.separator + shareSubPath;
                    		File subPathFile = new File(subscribedPath);
                    		if (!subPathFile.exists()) {
                    			System.err.println("The subscribed path does not exist with share-base " + shareBasePath + " and path " + shareSubPath);
                    		} else {
                    			long lastModifiedTime = lastKnownModifiedTime;
                    			List<String> changedFiles = new ArrayList<String>();
                    			System.out.println("Subscribed path '" + subPathFile.getAbsolutePath() + "' dir?" + subPathFile.isDirectory() + " file?" + subPathFile.isFile() + " canRead?" + subPathFile.canRead() + " exists?" + subPathFile.exists());
                    			System.out.println("Checking for updates to subscribed path " + subscribedPath + " since " + lastKnownModifiedTime + ".");
                    			if (subPathFile.isDirectory()) {
                    				long lastTimeOfAll = filesWithLatestTimestamp(subPathFile, lastKnownModifiedTime, subPathFile.getAbsolutePath() + File.separator, changedFiles);
                    				lastModifiedTime = Math.max(lastModifiedTime, lastTimeOfAll);
                    			} else if (subPathFile.isFile()) {
                    				lastModifiedTime = Math.max(lastModifiedTime, subPathFile.lastModified());
                    			}
                    			String changedFilesStr = "[";
                    			for (String file : changedFiles) {
                    				if (changedFilesStr.length() > 1) {
                    					changedFilesStr += ",";
                    				}
                    				changedFilesStr += "\"" + file + "\"";
                    			}
                    			changedFilesStr += "]";
                    			// Remember: if you change these after some live in the wild, you'll have to handle versioning.
                    			String commReply = 
                    				"{"
                    				+ "\"" + KEY_SHARE_BASE_INDEX + "\":" + shareBaseIndex
                    				+ ",\"" + KEY_SHARE_SUB_PATH + "\":\"" + shareSubPath + "\"" 
                    				+ ",\"" + KEY_LAST_MODIFIED_TIME + "\":" + lastModifiedTime 
                    				+ ",\"" + KEY_FILE_LIST + "\":" + changedFilesStr
                    				+ "}";
                    			System.out.println("Sending " + COMM_CHANGES_REPLY + " to friend " + source.getGuid() + ": " + commReply);
                    			source.getFriendConnection().send(new PlugInCommunication(COMM_CHANGES_PREFIX_AND_REPLY + "=" + commReply));
                    		}
                    	} catch (IOException e) {
                    		e.printStackTrace();
                    	}
                    }
        		}
        	}
        });
        
        // add listener for file timestamp replies, and schedule the download for each file
        core.addUICallback(new NonWindowUICallback() {
        	@Override
        	public void pluginCommunicationReceived(Friend source, String data) {
            	System.out.println("Received plugin comm from " + source + ": " + data);
        		if (data.startsWith(COMM_CHANGES_PREFIX_AND_REPLY)) {
        			String paramString = data.substring(COMM_CHANGES_PREFIX_AND_REPLY.length());
        			// strip off the "=" and the curly braces
        			String allKeyVals = paramString.substring(2, paramString.length() - 1);
        			// retrieve the share-base index
        			int nextComma = allKeyVals.indexOf(",");
        			int shareBaseIndex = new Integer(allKeyVals.substring(allKeyVals.indexOf(":") + 1, nextComma)).intValue();
        			// retrieve the share sub-path
        			String rest = allKeyVals.substring(nextComma + 1);
        			nextComma = rest.indexOf(",");
        			String shareSubPath = rest.substring(rest.indexOf(":") + 2, nextComma - 1); // without the quotes
        			//retrieve the last modified time
        			rest = rest.substring(nextComma + 1);
        			nextComma = rest.indexOf(",");
        			long lastModifiedTime = new Long(rest.substring(rest.indexOf(":") + 1, nextComma)).longValue();
        			// retrieve the file list
        			rest = rest.substring(nextComma + 1);
        			rest = rest.substring(rest.indexOf(":") + 1); // entire string array
        			rest = rest.substring(1, rest.length() - 1); // without the beginning/ending []
        			String[] fileList = {};
        			if (rest.length() > 0) {
            			rest = rest.substring(1, rest.length() - 1); // without the beginning/ending ""
            			fileList = rest.split("\",\"");
        			}
        			
                    if (shareBaseIndex == -1
                    	|| shareSubPath == null
                    	|| lastModifiedTime == -1
                    	|| fileList == null) {
    					System.err.println("Got some bad data for plugin " + COMM_CHANGES_PREFIX_AND_REPLY + ".  Expected JSON with keys for share-base index & sub-path & modified time & file list, but got this: " + paramString);
                    } else {
                    	System.out.println("Got file list: " + Arrays.asList(fileList));
                    	System.out.println("Got other stuff: " + shareBaseIndex + " " + shareSubPath + " " + lastModifiedTime);
                    	for (String file : fileList) {
                    		System.out.println("FriendFileChangeDetector plugin trying to download sharebase index " + shareBaseIndex + " and file or dir/ " + shareSubPath + file);
                    		
                    		final Friend subFriend = source;
                    		final int remoteShareBaseIndex = shareBaseIndex;
                    		final String remotePath = shareSubPath;
                    		final String remoteFile = file;
                    		final long remoteModTime = lastModifiedTime;
                    		core.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                		// find the subscription to get the right download directory
                                		FriendSubscription fsub = dbFriendSub.getFriendSubscription
                                		  (subFriend.getGuid(),
                                		   shareBasesForFriend.get(subFriend.getGuid())[remoteShareBaseIndex],
                                		   remotePath);
                                		
                                		// schedule the download
                                		System.out.println("Trying the ViewShareMDIWindow.download() approach for " + remotePath + remoteFile);
                                		//core.getFileManager().getDownloadStorage().addCustomDownload(subFriend.getGuid(), null, remotePath + remoteFile);
                                    	core.getFileManager().getDownloadStorage().addCustomDownload(subFriend.getGuid(), fsub.localPath, remotePath + remoteFile);
                                    	try {
                                    		subFriend.getFriendConnection().send(new GetHashesForPath(remoteShareBaseIndex, remotePath + remoteFile));
                                    	} catch (IOException e) {
                                    		System.err.println("Got an error trying to GetHashesForPath of file " + remoteFile);
                                    		e.printStackTrace();
                                    	}
                                    	
                                		//System.out.println("Trying the SearchMDIWindow.EVENT_download() approach.");
                                        //core.getNetworkManager().getDownloadManager().queDownload(n.getSh().getRoot(), name, n.getUserGuids());
                                    	
                                    	// now update our data to record the new time
                                    	String[] shareBases = shareBasesForFriend.get(subFriend.getGuid());
                                    	if (shareBases == null) {
                                			System.err.println("Strange: we got a timestamp reply from friend " + subFriend.getGuid() + ", but we have no share-bases recorded for them.");
                                    	} else if (remoteShareBaseIndex >= shareBases.length) {
                                			System.err.println("Strange: we got a timestamp reply from friend " + subFriend.getGuid() + ", but the share-base index of " + remoteShareBaseIndex + " isn't in our list of share-bases: " + Arrays.asList(shareBases) + ".");
                                    	} else {
                                    		String shareBase = shareBases[remoteShareBaseIndex];
                                    		try {
                                    			dbFriendSub.updateFriendSubscription(subFriend.getGuid(), shareBase, remotePath, remoteModTime);
                                    		} catch (SQLException e) {
                                    			System.err.println("Unable to update friend subscription for change on friend " + subFriend.getGuid() + " and share-base " + shareBase + " and path " + remotePath);
                                    			e.printStackTrace();
                                    		}
                                    	}
                                    } catch (Exception e) {
                                    	e.printStackTrace();
                                    }
                                }
                    		});
                    		
                    	}
                    }
        		}
        	}
        });
        
        System.out.println("Done initializing plugin for " + getClass().getName() + ".");
        
    }
    
    @Override
    public void shutdown() throws Exception {
    	dbFriendSub.shutdown();
    }
    
    @Override
    public ConsolePlugInExtension getConsoleExtensions() {
        return new ConsolePlugInExtension() {
        	public static final String TIMES_COMMAND = "times";
        	@Override
            public boolean handleLine(String line, Printer printer) {
                if (line.startsWith("newfsub")) {
                    printer.println("newfsub: " + line);
                	String[] values = line.split(" ");
                	for (int i = 2; i < 5; i++) {
                		if (values[i].startsWith("\"")) {
                			values[i] = values[i].substring(1);
                		}
                		if (values[i].endsWith("\"")) {
                			values[i] = values[i].substring(0, values[i].length() - 1);
                		}
                	}
                	FriendSubscription fsub = new FriendSubscription(new Integer(values[1]).intValue(), values[2], values[3], values[4], new Long(values[5]).longValue());
        			try {
        				dbFriendSub.createFriendSubscription(fsub);
                    	printer.println("Inserted " + fsub);
        			} catch (SQLException e) {
        				printer.println("Failed to insert " + fsub + " because: " + e.getMessage());
        				e.printStackTrace();
        			}
                        
                } else if (line.startsWith("getfsubs")) {
        			try {
        				List<FriendSubscription> fsubs = dbFriendSub.getFriendSubscriptions();
                    	printer.println("You have " + fsubs.size() + " friend subscription(s).");
                    	for (FriendSubscription fsub : fsubs) {
                    		printer.println("  " + fsub);
                    	}
        			} catch (SQLException e) {
        				printer.println("Failed to retrieve subscription records: " + e.getMessage());
        				e.printStackTrace();
        			}
                        
                } else if (line.startsWith(TIMES_COMMAND + " ")) {
                    ArrayList<String> params = new ArrayList<String>();
                    StringTokenizer st = new StringTokenizer(line.substring(TIMES_COMMAND.length() + 1));
                    while (st.hasMoreTokens()) {
                        params.add(st.nextToken());
                    }
                    if (params.size() < 4) {
                        printer.println("usage: times <friendNickname> <shareBaseIndex> <pathToFile> <lastModifiedTime>");
                    } else {
                    	Friend f = core.getFriendManager().getFriend(params.get(0));
                    	if (!f.isConnected()) {
                    		printer.println(f.getNickname() + " is not connected.");
                    	} else {
                    		try {
                    			sendLastModifiedQuery(f, Integer.valueOf(params.get(1)).intValue(), params.get(2), Long.valueOf(params.get(3)).longValue());
                    			printer.println("Request sent.");
                    		} catch (IOException e) {
                    			printer.println("Got an IOException: " + e.getMessage());
                    			printer.println("See log for stack trace.");
                    			e.printStackTrace();
                    		}
                    	}
                    }
                }
                // I could return for these commands because the API says I should, but I don't see any reason to stop another plugin from using the same string.
                return true;
            }
        };
    }
    
    /**
     * 
     * @param shareBase
     * @param shareBases
     * @return -1 if the shareBase is not found
     */
    private static int shareBaseIndex(String shareBase, String[] shareBases) {
    	int result = -1;
    	for (int i = 0; i < shareBases.length; i++) {
    		if (shareBases[i].equals(shareBase)) {
    			result = i;
    			break;
    		}
    	}
    	return result;
    }
    
	private void sendLastModifiedQuery(Friend friend, int shareBaseIndex, String shareSubPath, long lastKnownModified) throws IOException {
		// Remember: if you change these after some live in the wild, you'll have to handle versioning.
    	String commQuery = 
    		"{" 
			+ "\"" + KEY_SHARE_BASE_INDEX + "\":" + shareBaseIndex 
			+ ",\"" + KEY_SHARE_SUB_PATH + "\":\"" + shareSubPath + "\""
			+ ",\"" + KEY_LAST_KNOWN_MODIFIED_TIME + "\":" + lastKnownModified
			+ "}";
    	System.out.println("Sending " + COMM_CHANGES_QUERY + " to friend " + friend.getGuid() + ": " + commQuery);
		friend.getFriendConnection().send(new PlugInCommunication(COMM_CHANGES_PREFIX_AND_QUERY + "=" + commQuery));
    }
    
    /**
     * 
     * @param baseDir directory underneath which to look for file updates
     * @param friendsLastKnownTimestamp time that friend was last updated
     * @param pathBelowSubPath is the path where the search started, and is used to truncate the paths of the changedFiles
     * @param changedFiles all the files that have changed since friendsLastKnownTimestamp, modified as we find more
     * @return the latest timestamp of the collected files
     */
    private long filesWithLatestTimestamp(File baseDir, long friendsLastKnownTimestamp, String pathBelowSubPath, List<String> changedFiles) {
    	long result = baseDir.lastModified();
    	File[] nestedFiles = baseDir.listFiles();
		System.out.println("FileSubSearch: working on dir " + baseDir.getName() + " with contents " + Arrays.asList(nestedFiles));
    	for (File file : nestedFiles) {
    		if (file.isDirectory()) {
    			System.out.println("FileSubSearch: " + file.getName() + " is dir, so will recurse.");
    			long nestedResult = filesWithLatestTimestamp(file, friendsLastKnownTimestamp, pathBelowSubPath, changedFiles);
    			result = Math.max(result, nestedResult);
    			System.out.println("FileSubSearch: latest time from recursion is now " + result);
    		} else if (file.isFile()) {
    			System.out.println("FileSubSearch: " + file.getName() + " is a file.");
    			if (file.lastModified() > friendsLastKnownTimestamp) {
        			System.out.println("FileSubSearch: " + file.getName() + " is a file that is newer, so we're adding it.");
    				changedFiles.add(file.getAbsolutePath().substring(pathBelowSubPath.length()));
    				result = Math.max(result, file.lastModified());
        			System.out.println("FileSubSearch: latest time is now " + result);
    			}
    		} else {
    			System.err.println("FileSubSearch: Strange... found a non-file, non-directory file (which we will ignore): " + file.getAbsolutePath());
    		}
    	}
    	return result;
    }
    
}
