/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.search;

import java.util.ArrayList;
import java.util.Iterator;
import org.eclipse.search.ui.IWorkingSet;
import org.eclipse.search.ui.SearchUI;

/**
 * Contribute Java search specific menu elements.
 */
public class DeclarationsSearchGroup extends JavaSearchSubGroup  {

	public static final String GROUP_NAME= SearchMessages.getString("group.declarations"); //$NON-NLS-1$

	protected ElementSearchAction[] getActions() {
		ArrayList actions= new ArrayList(ElementSearchAction.LRU_WORKINGSET_LIST_SIZE + 3);
		actions.add(new FindDeclarationsAction());
		actions.add(new FindDeclarationsInHierarchyAction());
		actions.add(new FindDeclarationsInWorkingSetAction());

		Iterator iter= ElementSearchAction.getLRUWorkingSets().sortedIterator();
		while (iter.hasNext()) {
			IWorkingSet workingSet= (IWorkingSet)iter.next();
			actions.add(new WorkingSetAction(new FindDeclarationsInWorkingSetAction(workingSet), workingSet.getName()));
		}
		return (ElementSearchAction[])actions.toArray(new ElementSearchAction[actions.size()]);
	}

	protected String getName() {
		return GROUP_NAME;
	}
}
