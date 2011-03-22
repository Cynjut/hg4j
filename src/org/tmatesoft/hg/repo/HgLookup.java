/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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

import java.io.File;
import java.io.IOException;

import org.tmatesoft.hg.core.HgException;

/**
 * Utility methods to find Mercurial repository at a given location
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgLookup {

	public HgRepository detectFromWorkingDir() throws HgException {
		return detect(System.getProperty("user.dir"));
	}

	public HgRepository detect(String location) throws HgException {
		return detect(new File(location));
	}

	// look up in specified location and above
	public HgRepository detect(File location) throws HgException {
		File dir = location.getAbsoluteFile();
		File repository;
		do {
			repository = new File(dir, ".hg");
			if (repository.exists() && repository.isDirectory()) {
				break;
			}
			repository = null;
			dir = dir.getParentFile();
			
		} while(dir != null);
		if (repository == null) {
			// return invalid repository
			return new HgRepository(location.getPath());
		}
		try {
			String repoPath = repository.getParentFile().getCanonicalPath();
			return new HgRepository(repoPath, repository);
		} catch (IOException ex) {
			throw new HgException(location.toString(), ex);
		}
	}
}