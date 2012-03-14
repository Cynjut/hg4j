/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import static org.tmatesoft.hg.repo.HgRepository.*;

import org.tmatesoft.hg.internal.Experimental;

/**
 * Use of revision or revision local index that is not valid for a given revlog.
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
@Experimental(reason="1) Whether to use checked or runtime exception is not yet decided. 2) Perhaps, its use not bound to wrong arguments")
public class HgInvalidRevisionException extends IllegalArgumentException {
	private Nodeid rev;
	private Integer revIdx = BAD_REVISION;
	// next two make sense only when revIdx is present
	private int rangeLeftBoundary = BAD_REVISION, rangeRightBoundary = BAD_REVISION;

	/**
	 * 
	 * this exception is not expected to be initialized with another exception, although those who need to, 
	 * may still use {@link #initCause(Throwable)}
	 * @param message optional description of the issue
	 * @param revision invalid revision, may be  <code>null</code> if revisionIndex is used
	 * @param revisionIndex invalid revision index, may be <code>null</code> if not known and revision is supplied 
	 */
	public HgInvalidRevisionException(String message, Nodeid revision, Integer revisionIndex) {
		super(message);
		assert revision != null || revisionIndex != null; 
		rev = revision;
		revIdx = revisionIndex;
	}

	public HgInvalidRevisionException(Nodeid revision) {
		this(null, revision, null);
	}
	
	public HgInvalidRevisionException(int revisionIndex) {
		this(null, null, revisionIndex);
	}

	public Nodeid getRevision() {
		return rev;
	}
	
	public Integer getRevisionIndex() {
		return revIdx;
	}

	public HgInvalidRevisionException setRevision(Nodeid revision) {
		assert revision != null;
		rev = revision;
		return this;
	}
	
	// int, not Integer is on purpose, not to clear exception completely
	public HgInvalidRevisionException setRevisionIndex(int revisionIndex) {
		revIdx = revisionIndex;
		return this;
	}
	
	public HgInvalidRevisionException setRevisionIndex(int revisionIndex, int rangeLeft, int rangeRight) {
		revIdx = revisionIndex;
		rangeLeftBoundary = rangeLeft;
		rangeRightBoundary = rangeRight;
		return this;
	}

	public boolean isRevisionSet() {
		return rev != null;
	}
	
	public boolean isRevisionIndexSet() {
		return revIdx != BAD_REVISION;
	}

	@Override
	public String getMessage() {
		String msg = super.getMessage();
		if (msg != null) {
			return msg;
		}
		StringBuilder sb = new StringBuilder();
		if (rev != null) {
			sb.append("Revision:");
			sb.append(rev.shortNotation());
			sb.append(' ');
		}
		if (revIdx != null) {
			String sr;
			switch (revIdx) {
			case BAD_REVISION : sr = "UNKNOWN"; break;
			case TIP : sr = "TIP"; break;
			case WORKING_COPY: sr = "WORKING-COPY"; break;
			case NO_REVISION : sr = "NO REVISION"; break;
			default : sr = revIdx.toString();
			}
			if (rangeLeftBoundary != BAD_REVISION || rangeRightBoundary != BAD_REVISION) {
				sb.append(String.format("%s is not from [%d..%d]", sr, rangeLeftBoundary, rangeRightBoundary));
			} else {
				sb.append(sr);
			}
		}
		return sb.toString();
	}
}
