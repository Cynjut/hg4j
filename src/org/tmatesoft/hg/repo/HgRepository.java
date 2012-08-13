/*
 * Copyright (c) 2010-2012 TMate Software Ltd
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepositoryFiles.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.SoftReference;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Filter;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.internal.SubrepoManager;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;
import org.tmatesoft.hg.util.ProgressSupport;



/**
 * Shall be as state-less as possible, all the caching happens outside the repo, in commands/walkers
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgRepository {

	// IMPORTANT: if new constants added, consider fixing HgInternals#wrongRevisionIndex and HgInvalidRevisionException#getMessage

	/**
	 * Revision index constant to indicate most recent revision
	 */
	public static final int TIP = -3; // XXX TIP_REVISION?

	/**
	 * Revision index constant to indicate invalid revision index value. 
	 * Primary use is default/uninitialized values where user input is expected and as return value where 
	 * an exception (e.g. {@link HgInvalidRevisionException}) is not desired
	 */
	public static final int BAD_REVISION = Integer.MIN_VALUE; // XXX INVALID_REVISION?

	/**
	 * Revision index constant to indicate working copy
	 */
	public static final int WORKING_COPY = -2; // XXX WORKING_COPY_REVISION?
	
	/**
	 * Constant ({@value #NO_REVISION}) to indicate revision absence or a fictitious revision of an empty repository.
	 * 
	 * <p>Revision absence is vital e.g. for missing parent from {@link HgChangelog#parents(int, int[], byte[], byte[])} call and
	 * to report cases when changeset records no corresponding manifest 
	 * revision {@link HgManifest#walk(int, int, org.tmatesoft.hg.repo.HgManifest.Inspector)}.
	 * 
	 * <p> Use as imaginary revision/empty repository is handy as an argument (contrary to {@link #BAD_REVISION})
	 * e.g in a status operation to visit changes from the very beginning of a repository.
	 */
	public static final int NO_REVISION = -1;
	
	/**
	 * Name of the primary branch, "default".
	 */
	public static final String DEFAULT_BRANCH_NAME = "default";

	// temp aux marker method
	public static IllegalStateException notImplemented() {
		return new IllegalStateException("Not implemented");
	}
	
	private final File repoDir; // .hg folder
	private final File workingDir; // .hg/../
	private final String repoLocation;
	private final DataAccessProvider dataAccess;
	private final PathRewrite normalizePath; // normalized slashes but otherwise regular file names
	private final PathRewrite dataPathHelper; // access to file storage area (usually under .hg/store/data/), with filenames mangled  
	private final PathRewrite repoPathHelper; // access to system files
	private final SessionContext sessionContext;

	private HgChangelog changelog;
	private HgManifest manifest;
	private HgTags tags;
	private HgBranches branches;
	private HgMergeState mergeState;
	private SubrepoManager subRepos;
	private HgBookmarks bookmarks;

	// XXX perhaps, shall enable caching explicitly
	private final HashMap<Path, SoftReference<RevlogStream>> streamsCache = new HashMap<Path, SoftReference<RevlogStream>>();
	
	private final org.tmatesoft.hg.internal.Internals impl;
	private HgIgnore ignore;
	private HgRepoConfig repoConfig;
	
	/*
	 * TODO [post-1.0] move to a better place, e.g. WorkingCopy container that tracks both dirstate and branches 
	 * (and, perhaps, undo, lastcommit and other similar information), and is change listener so that we don't need to
	 * worry about this cached value become stale
	 */
	private String wcBranch;

	
	HgRepository(String repositoryPath) {
		repoDir = null;
		workingDir = null;
		repoLocation = repositoryPath;
		dataAccess = null;
		dataPathHelper = repoPathHelper = null;
		normalizePath = null;
		sessionContext = null;
		impl = null;
	}
	
	/**
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	HgRepository(SessionContext ctx, String repositoryPath, File repositoryRoot) throws HgRuntimeException {
		assert ".hg".equals(repositoryRoot.getName()) && repositoryRoot.isDirectory();
		assert repositoryPath != null; 
		assert repositoryRoot != null;
		assert ctx != null;
		repoDir = repositoryRoot;
		workingDir = repoDir.getParentFile();
		if (workingDir == null) {
			throw new IllegalArgumentException(repoDir.toString());
		}
		impl = new org.tmatesoft.hg.internal.Internals(ctx);
		repoLocation = repositoryPath;
		sessionContext = ctx;
		dataAccess = new DataAccessProvider(ctx);
		impl.parseRequires(this, new File(repoDir, "requires"));
		normalizePath = impl.buildNormalizePathRewrite(); 
		dataPathHelper = impl.buildDataFilesHelper();
		repoPathHelper = impl.buildRepositoryFilesHelper();
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + getLocation() + (isInvalid() ? "(BAD)" : "") + "]";
	}                         
	
	public String getLocation() {
		return repoLocation;
	}

	public boolean isInvalid() {
		return repoDir == null || !repoDir.exists() || !repoDir.isDirectory();
	}
	
	public HgChangelog getChangelog() {
		if (changelog == null) {
			CharSequence storagePath = repoPathHelper.rewrite("00changelog.i");
			RevlogStream content = resolve(Path.create(storagePath), true);
			changelog = new HgChangelog(this, content);
		}
		return changelog;
	}
	
	public HgManifest getManifest() {
		if (manifest == null) {
			RevlogStream content = resolve(Path.create(repoPathHelper.rewrite("00manifest.i")), true);
			manifest = new HgManifest(this, content, impl.buildFileNameEncodingHelper());
		}
		return manifest;
	}
	
	/**
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgTags getTags() throws HgInvalidControlFileException {
		if (tags == null) {
			tags = new HgTags(this);
			HgDataFile hgTags = getFileNode(HgTags.getPath());
			if (hgTags.exists()) {
				for (int i = 0; i <= hgTags.getLastRevision(); i++) { // TODO post-1.0 in fact, would be handy to have walk(start,end) 
					// method for data files as well, though it looks odd.
					try {
						ByteArrayChannel sink = new ByteArrayChannel();
						hgTags.content(i, sink);
						final String content = new String(sink.toArray(), "UTF8");
						tags.readGlobal(new StringReader(content));
					} catch (CancelledException ex) {
						 // IGNORE, can't happen, we did not configure cancellation
						getContext().getLog().dump(getClass(), Debug, ex, null);
					} catch (IOException ex) {
						// UnsupportedEncodingException can't happen (UTF8)
						// only from readGlobal. Need to reconsider exceptions thrown from there:
						// BufferedReader wraps String and unlikely to throw IOException, perhaps, log is enough?
						getContext().getLog().dump(getClass(), Error, ex, null);
						// XXX need to decide what to do this. failure to read single revision shall not break complete cycle
					}
				}
			}
			File file2read = null;
			try {
				file2read = new File(getWorkingDir(), HgTags.getPath());
				tags.readGlobal(file2read); // XXX replace with HgDataFile.workingCopy
				file2read = new File(repoDir, HgLocalTags.getName());
				tags.readLocal(file2read);
			} catch (IOException ex) {
				getContext().getLog().dump(getClass(), Error, ex, null);
				throw new HgInvalidControlFileException("Failed to read tags", ex, file2read);
			}
		}
		return tags;
	}
	
	/**
	 * Access branch information
	 * @return branch manager instance, never <code>null</code>
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgBranches getBranches() throws HgInvalidControlFileException {
		if (branches == null) {
			branches = new HgBranches(this);
			branches.collect(ProgressSupport.Factory.get(null));
		}
		return branches;
	}

	/**
	 * Access state of the recent merge
	 * @return merge state facility, never <code>null</code> 
	 */
	public HgMergeState getMergeState() {
		if (mergeState == null) {
			mergeState = new HgMergeState(this);
		}
		return mergeState;
	}
	
	public HgDataFile getFileNode(String path) {
		CharSequence nPath = normalizePath.rewrite(path);
		CharSequence storagePath = dataPathHelper.rewrite(nPath);
		RevlogStream content = resolve(Path.create(storagePath), false);
		Path p = Path.create(nPath);
		if (content == null) {
			return new HgDataFile(this, p);
		}
		return new HgDataFile(this, p, content);
	}

	public HgDataFile getFileNode(Path path) {
		CharSequence storagePath = dataPathHelper.rewrite(path.toString());
		RevlogStream content = resolve(Path.create(storagePath), false);
		// XXX no content when no file? or HgDataFile.exists() to detect that?
		if (content == null) {
			return new HgDataFile(this, path);
		}
		return new HgDataFile(this, path, content);
	}

	/* clients need to rewrite path from their FS to a repository-friendly paths, and, perhaps, vice versa*/
	public PathRewrite getToRepoPathHelper() {
		return normalizePath;
	}

	/**
	 * @return pair of values, {@link Pair#first()} and {@link Pair#second()} are respective parents, never <code>null</code>.
	 * @throws HgInvalidControlFileException if attempt to read information about working copy parents from dirstate failed 
	 */
	public Pair<Nodeid,Nodeid> getWorkingCopyParents() throws HgInvalidControlFileException {
		return HgDirstate.readParents(this, new File(repoDir, Dirstate.getName()));
	}
	
	/**
	 * @return name of the branch associated with working directory, never <code>null</code>.
	 * @throws HgInvalidControlFileException if attempt to read branch name failed.
	 */
	public String getWorkingCopyBranchName() throws HgInvalidControlFileException {
		if (wcBranch == null) {
			wcBranch = HgDirstate.readBranch(this, new File(repoDir, "branch"));
		}
		return wcBranch;
	}

	/**
	 * @return location where user files (shall) reside
	 */
	public File getWorkingDir() {
		return workingDir;
	}
	
	/**
	 * Provides access to sub-repositories defined in this repository. Enumerated  sub-repositories are those directly
	 * known, not recursive collection of all nested sub-repositories.
	 * @return list of all known sub-repositories in this repository, or empty list if none found.
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public List<HgSubrepoLocation> getSubrepositories() throws HgInvalidControlFileException {
		if (subRepos == null) {
			subRepos = new SubrepoManager(this);
			subRepos.read();
		}
		return subRepos.all();
	}

	
	public HgRepoConfig getConfiguration() /* XXX throws HgInvalidControlFileException? Description of the exception suggests it is only for files under ./hg/*/ {
		if (repoConfig == null) {
			try {
				ConfigFile configFile = impl.readConfiguration(this, getRepositoryRoot());
				repoConfig = new HgRepoConfig(configFile);
			} catch (IOException ex) {
				String m = "Errors while reading user configuration file";
				getContext().getLog().dump(getClass(), Warn, ex, m);
				return new HgRepoConfig(new ConfigFile(getContext())); // empty config, do not cache, allow to try once again
				//throw new HgInvalidControlFileException(m, ex, null);
			}
		}
		return repoConfig;
	}

	// shall be of use only for internal classes 
	/*package-local*/ File getRepositoryRoot() {
		return repoDir;
	}
	
	/*package-local, debug*/String getStoragePath(HgDataFile df) {
		// may come handy for debug
		return dataPathHelper.rewrite(df.getPath().toString()).toString();
	}

	// XXX package-local, unless there are cases when required from outside (guess, working dir/revision walkers may hide dirstate access and no public visibility needed)
	// XXX consider passing Path pool or factory to produce (shared) Path instead of Strings
	/*package-local*/ final HgDirstate loadDirstate(Path.Source pathFactory) throws HgInvalidControlFileException {
		PathRewrite canonicalPath = null;
		if (!impl.isCaseSensitiveFileSystem()) {
			canonicalPath = new PathRewrite() {

				public CharSequence rewrite(CharSequence path) {
					return path.toString().toLowerCase();
				}
			};
		}
		File dirstateFile = new File(repoDir, Dirstate.getName());
		HgDirstate ds = new HgDirstate(this, dirstateFile, pathFactory, canonicalPath);
		ds.read(impl.buildFileNameEncodingHelper());
		return ds;
	}

	/**
	 * Access to configured set of ignored files.
	 * @see HgIgnore#isIgnored(Path)
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgIgnore getIgnore() throws HgInvalidControlFileException {
		// TODO read config for additional locations
		if (ignore == null) {
			ignore = new HgIgnore(getToRepoPathHelper());
			File ignoreFile = new File(getWorkingDir(), HgIgnore.getPath());
			try {
				final List<String> errors = ignore.read(ignoreFile);
				if (errors != null) {
					getContext().getLog().dump(getClass(), Warn, "Syntax errors parsing %s:\n%s", ignoreFile.getName(), Internals.join(errors, ",\n"));
				}
			} catch (IOException ex) {
				final String m = String.format("Error reading %s file", ignoreFile);
				throw new HgInvalidControlFileException(m, ex, ignoreFile);
			}
		}
		return ignore;
	}

	/**
	 * Mercurial saves message user has supplied for a commit to facilitate message re-use in case commit fails.
	 * This method provides this saved message.
	 *  
	 * @return message used for last commit attempt, or <code>null</code> if none
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public String getCommitLastMessage() throws HgInvalidControlFileException {
		File lastMessage = new File(repoDir, LastMessage.getPath());
		if (!lastMessage.canRead()) {
			return null;
		}
		FileReader fr = null;
		try {
			fr = new FileReader(lastMessage);
			CharBuffer cb = CharBuffer.allocate(Internals.ltoi(lastMessage.length()));
			fr.read(cb);
			return cb.flip().toString();
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Can't retrieve message of last commit attempt", ex, lastMessage);
		} finally {
			if (fr != null) {
				try {
					fr.close();
				} catch (IOException ex) {
					getContext().getLog().dump(getClass(), Warn, "Failed to close %s after read", lastMessage);
				}
			}
		}
	}

	private HgRepositoryLock wdLock, storeLock;

	/**
	 * PROVISIONAL CODE, DO NOT USE
	 * 
	 * Access repository lock that covers non-store parts of the repository (dirstate, branches, etc - 
	 * everything that has to do with working directory state).
	 * 
	 * Note, the lock object returned merely gives access to lock mechanism. NO ACTUAL LOCKING IS DONE.
	 * Use {@link HgRepositoryLock#acquire()} to actually lock the repository.  
	 *   
	 * @return lock object, never <code>null</code>
	 */
	@Experimental(reason="WORK IN PROGRESS")
	public HgRepositoryLock getWorkingDirLock() {
		if (wdLock == null) {
			int timeout = getLockTimeout();
			File lf = new File(getRepositoryRoot(), "wlock");
			synchronized (this) {
				if (wdLock == null) {
					wdLock = new HgRepositoryLock(lf, timeout);
				}
			}
		}
		return wdLock;
	}

	@Experimental(reason="WORK IN PROGRESS")
	public HgRepositoryLock getStoreLock() {
		if (storeLock == null) {
			int timeout = getLockTimeout();
			File fl = new File(getRepositoryRoot(), repoPathHelper.rewrite("lock").toString());
			synchronized (this) {
				if (storeLock == null) {
					storeLock = new HgRepositoryLock(fl, timeout);
				}
			}
		}
		return storeLock;
	}

	/**
	 * Access bookmarks-related functionality
	 * @return facility to manage bookmarks, never <code>null</code>
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgBookmarks getBookmarks() throws HgInvalidControlFileException {
		if (bookmarks == null) {
			bookmarks = new HgBookmarks(this);
			bookmarks.read();
		}
		return bookmarks;
	}

	/*package-local*/ DataAccessProvider getDataAccess() {
		return dataAccess;
	}

	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 * path - repository storage path (i.e. one usually with .i or .d)
	 */
	/*package-local*/ RevlogStream resolve(Path path, boolean shallFakeNonExistent) {
		final SoftReference<RevlogStream> ref = streamsCache.get(path);
		RevlogStream cached = ref == null ? null : ref.get();
		if (cached != null) {
			return cached;
		}
		File f = new File(repoDir, path.toString());
		if (f.exists()) {
			RevlogStream s = new RevlogStream(dataAccess, f);
			if (impl.shallCacheRevlogs()) {
				streamsCache.put(path, new SoftReference<RevlogStream>(s));
			}
			return s;
		} else {
			if (shallFakeNonExistent) {
				try {
					File fake = File.createTempFile(f.getName(), null);
					fake.deleteOnExit();
					return new RevlogStream(dataAccess, fake);
				} catch (IOException ex) {
					getContext().getLog().dump(getClass(), Info, ex, null);
				}
			}
		}
		return null; // XXX empty stream instead?
	}
	
	/*package-local*/ List<Filter> getFiltersFromRepoToWorkingDir(Path p) {
		return instantiateFilters(p, new Filter.Options(Filter.Direction.FromRepo));
	}

	/*package-local*/ List<Filter> getFiltersFromWorkingDirToRepo(Path p) {
		return instantiateFilters(p, new Filter.Options(Filter.Direction.ToRepo));
	}
	
	/*package-local*/ File getFile(HgDataFile dataFile) {
		return new File(getWorkingDir(), dataFile.getPath().toString());
	}
	
	/*package-local*/ SessionContext getContext() {
		return sessionContext;
	}
	
	/*package-local*/ Internals getImplHelper() {
		return impl;
	}

	private List<Filter> instantiateFilters(Path p, Filter.Options opts) {
		List<Filter.Factory> factories = impl.getFilters(this);
		if (factories.isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<Filter> rv = new ArrayList<Filter>(factories.size());
		for (Filter.Factory ff : factories) {
			Filter f = ff.create(p, opts);
			if (f != null) {
				rv.add(f);
			}
		}
		return rv;
	}

	private int getLockTimeout() {
		return getConfiguration().getIntegerValue("ui", "timeout", 600);
	}
}
