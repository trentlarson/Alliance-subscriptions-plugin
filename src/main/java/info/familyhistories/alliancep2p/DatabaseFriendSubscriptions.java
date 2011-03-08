package info.familyhistories.alliancep2p;

import info.familyhistories.alliancep2p.FriendFileChangeDetectorPlugIn.FriendSubscription;
import info.familyhistories.alliancep2p.FriendFileChangeDetectorPlugIn.FriendSubscriptionPersistence;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.alliance.core.CoreSubsystem;
import org.alliance.core.file.h2database.DatabaseCore;

/**
 * All the DB operations seem to work.
 * However, the app often fails to download the files that I request for custom download.
 * Sometimes it downloads all; sometimes it downloads just one; sometimes none.
 * The one way I've found to force it to download at least one file is to set the 
 * LAST_MODIFIED_TIME to 0... which seems to indicate that it's a problem with my plugin,
 * but it gets to the download queueing code just fine.  Very strange.
 */
public class DatabaseFriendSubscriptions implements FriendSubscriptionPersistence {
	
	private Connection conn = null;
	
	public DatabaseFriendSubscriptions(CoreSubsystem core) throws SQLException {
		
		// grab a connection to the same database
		/**
		 * 
		 * This approach grabs the connection used in the DatabaseCore.
		 * 
		 **/
		conn = (Connection) getFieldVal("conn", core.getFileManager().getDbCore());
		
		/**
		// grab a connection to the same database
		 * 
		 * This approach creates our own connection with the DB settings.
		 * It actually seems to work, which surprises me because other programs
		 * give errors when I try to open another DB connection while the app
		 * has one open already.
		 *
		 * Put this line at the beginning of the class:
		 * 
	    private static String DRIVER, DRIVERURL, TYPE, OPTIONS, USER, PASSWORD;
	    
	     * 
	     * ... and put this line inside the shutdown() method:
	     * 
		if (conn != null) try { conn.close(); } catch (SQLException e) {}
	 	
	 	 * 
	 	 * ... and just uncomment the rest of this here:
	 	 * 
		DRIVER = (String) getFieldVal("DRIVER");
		DRIVERURL = (String) getFieldVal("DRIVERURL");
		TYPE = (String) getFieldVal("TYPE");
		OPTIONS = (String) getFieldVal("OPTIONS");
		USER = (String) getFieldVal("USER");
		PASSWORD = (String) getFieldVal("PASSWORD");
        String path = core.getSettings().getInternal().getDatabasefile();
        try {
        	Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
        	throw new SQLException(e);
        }
        conn = DriverManager.getConnection(DRIVERURL + TYPE + path + OPTIONS, USER, PASSWORD);
		 **/
		
        createTable(conn);
	}
	
	public void shutdown() {
	}
	
	private void createTable(Connection conn) throws SQLException {
    	Statement stmt = null;
        try {
        	StringBuilder createSql = new StringBuilder();
        	createSql.append("CREATE TABLE IF NOT EXISTS friend_subscriptions(");
        	createSql.append("  friend_guid bigint NOT NULL, ");
        	createSql.append("  share_base_path character varying(4096) NOT NULL, ");
        	createSql.append("  share_sub_path character varying(4096), ");
        	createSql.append("  local_path character varying(4096), ");
        	createSql.append("  last_any_modified bigint NOT NULL, ");
        	createSql.append("  CONSTRAINT pk_friend_subscriptions PRIMARY KEY (friend_guid, share_base_path, share_sub_path));");
    		stmt = conn.createStatement();
            //int result = 
            stmt.executeUpdate(createSql.toString());
            //System.out.println("Created " + result + " FRIEND_SUBSCRIPTIONS tables.");
            stmt.close();
            
    		String indexSql = "CREATE INDEX IF NOT EXISTS idx_friend_subscriptions ON friend_subscriptions(friend_guid);";
			stmt = conn.createStatement();
			stmt.executeUpdate(indexSql.toString());
			stmt.close();
            
        } finally {
        	if (stmt != null) try { stmt.close(); } catch (SQLException e) {}
        }
	}
	
	
	
	public boolean createFriendSubscription(FriendSubscription fsub) throws SQLException {
		boolean result = false;
		PreparedStatement pstmt = null;
		ResultSet rset = null;
		try {
			String sql = "INSERT INTO friend_subscriptions (friend_guid, share_base_path, share_sub_path, local_path, last_any_modified) VALUES (?, ?, ?, ?, ?);";
			pstmt = conn.prepareStatement(sql);
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, fsub.guid);
			pstmt.setString(2, fsub.shareBasePath);
			pstmt.setString(3, fsub.shareSubPath);
			pstmt.setString(4, fsub.localPath);
			pstmt.setLong(5, fsub.lastKnownModified);
			result = pstmt.execute();
		} finally {
        	if (rset != null) try { rset.close(); } catch (SQLException e) {}
        	if (pstmt != null) try { pstmt.close(); } catch (SQLException e) {}
		}
		return result;
	}
	
	public List<FriendSubscription> getFriendSubscriptions() throws SQLException {
		List<FriendSubscription> result = new ArrayList<FriendSubscription>();
		PreparedStatement pstmt = null;
		ResultSet rset = null;
		try {
			String sql = "SELECT friend_guid, share_base_path, share_sub_path, local_path, last_any_modified FROM friend_subscriptions;";
			pstmt = conn.prepareStatement(sql);
			rset = pstmt.executeQuery();
			while (rset.next()) {
				FriendSubscription fsub = new FriendSubscription(rset.getInt(1), rset.getString(2), rset.getString(3), rset.getString(4), rset.getLong(5));
				result.add(fsub);
			}
		} finally {
        	if (rset != null) try { rset.close(); } catch (SQLException e) {}
        	if (pstmt != null) try { pstmt.close(); } catch (SQLException e) {}
		}
		return result;
	}
	
	public List<FriendSubscription> getFriendSubscriptions(Integer guid) throws SQLException {
		List<FriendSubscription> result = new ArrayList<FriendSubscription>();
		PreparedStatement pstmt = null;
		ResultSet rset = null;
		try {
			String sql = "SELECT friend_guid, share_base_path, share_sub_path, local_path, last_any_modified FROM friend_subscriptions WHERE friend_guid = ?;";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, guid);
			rset = pstmt.executeQuery();
			while (rset.next()) {
				FriendSubscription fsub = new FriendSubscription(rset.getInt(1), rset.getString(2), rset.getString(3), rset.getString(4), rset.getLong(5));
				result.add(fsub);
			}
		} finally {
        	if (rset != null) try { rset.close(); } catch (SQLException e) {}
        	if (pstmt != null) try { pstmt.close(); } catch (SQLException e) {}
		}
		return result;
	}
	
	/**
	 * Note that we're assuming only a single FriendSubscription for this combination.
	 * ... which we should enforce in the database!  REFACTOR
	 * 
	 * @param guid
	 * @param shareBasePath
	 * @param shareSubPath
	 * @return
	 * @throws SQLException
	 */
	public FriendSubscription getFriendSubscription(Integer guid, String shareBasePath, String shareSubPath) throws SQLException {
		FriendSubscription result = null;
		PreparedStatement pstmt = null;
		ResultSet rset = null;
		try {
			String sql = "SELECT friend_guid, share_base_path, share_sub_path, local_path, last_any_modified FROM friend_subscriptions WHERE friend_guid = ? AND share_base_path = ? AND share_sub_path = ?;";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, guid);
			pstmt.setString(2, shareBasePath);
			pstmt.setString(3, shareSubPath);
			rset = pstmt.executeQuery();
			if (rset.next()) {
				result = new FriendSubscription(rset.getInt(1), rset.getString(2), rset.getString(3), rset.getString(4), rset.getLong(5));
			}
			if (rset.next()) {
				System.err.println("Warning: there are multiple FriendSubscriptions with this criteria.  We'll ignore all but the first.  friend guid: " + guid + ", share base path: " + shareBasePath + ", share sub path: " + shareSubPath);
			}
		} finally {
        	if (rset != null) try { rset.close(); } catch (SQLException e) {}
        	if (pstmt != null) try { pstmt.close(); } catch (SQLException e) {}
		}
		return result;
	}
	
	/**
	 * Set the lastModifiedTime of this friend subscription.
	 */
	public boolean updateFriendSubscription(Integer friendGuid, String shareBasePath, String shareSubPath, long lastModifiedTime) throws SQLException {
		boolean result = false;
		PreparedStatement pstmt = null;
		ResultSet rset = null;
		try {
			String sql = "UPDATE friend_subscriptions SET last_any_modified = ? WHERE friend_guid = ? and share_base_path = ? and share_sub_path = ?;";
			pstmt = conn.prepareStatement(sql);
			pstmt.setLong(1, lastModifiedTime);
			pstmt.setInt(2, friendGuid);
			pstmt.setString(3, shareBasePath);
			pstmt.setString(4, shareSubPath);
			result = pstmt.execute();
		} finally {
        	if (rset != null) try { rset.close(); } catch (SQLException e) {}
        	if (pstmt != null) try { pstmt.close(); } catch (SQLException e) {}
		}
		return result;
	}
	
	
	
	private Object getFieldVal(String fieldName, DatabaseCore dbCore) {
		try {
			Field coreField = DatabaseCore.class.getDeclaredField(fieldName);
			coreField.setAccessible(true);
			return coreField.get(dbCore);
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		return null;
	}
	
}
