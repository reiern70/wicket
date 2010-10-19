/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.markup.html.tree;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;

import org.apache.wicket.Component;
import org.apache.wicket.IRequestTarget;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.ResourceReference;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.behavior.IBehavior;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.MarkupStream;
import org.apache.wicket.markup.html.JavascriptPackageResource;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.internal.HtmlHeaderContainer;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.markup.html.resources.JavascriptResourceReference;
import org.apache.wicket.model.IDetachable;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.util.string.AppendingStringBuffer;


/**
 * This class encapsulates the logic for displaying and (partial) updating the tree. Actual
 * presentation is out of scope of this class. User should derive they own tree (if needed) from
 * {@link BaseTree} (recommended).
 * 
 * @author Matej Knopp
 */
public abstract class AbstractTree extends Panel
	implements
		ITreeStateListener,
		TreeModelListener,
		AjaxRequestTarget.ITargetRespondListener
{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Interface for visiting individual tree items.
	 */
	private static interface IItemCallback
	{
		/**
		 * Visits the tree item.
		 * 
		 * @param item
		 *            the item to visit
		 */
		void visitItem(TreeItem item);
	}

	/**
	 * This class represents one row in rendered tree (TreeNode). Only TreeNodes that are visible
	 * (all their parent are expanded) have TreeItem created for them.
	 */
	private final class TreeItem extends WebMarkupContainer
	{
		/**
		 * whether this tree item should also render it's children to response. this is set if we
		 * need the whole subtree rendered as one component in ajax response, so that we can replace
		 * it in one step (replacing individual rows is very slow in javascript, therefore we
		 * replace the whole subtree)
		 */
		private final static int FLAG_RENDER_CHILDREN = FLAG_RESERVED8;

		private static final long serialVersionUID = 1L;

		/**
		 * tree item children - we need this to traverse items in correct order when rendering
		 */
		private List<TreeItem> children = null;

		/** tree item level - how deep is this item in tree */
		private final int level;

		private final TreeItem parent;

		/**
		 * Construct.
		 * 
		 * @param parent
		 *            parent node
		 * @param id
		 *            The component id
		 * @param node
		 *            tree node
		 * @param level
		 *            current level
		 */
		public TreeItem(TreeItem parent, String id, final Object node, int level)
		{
			super(id, new Model<Serializable>((Serializable)node));

			this.parent = parent;

			nodeToItemMap.put(node, this);
			this.level = level;
			setOutputMarkupId(true);

			// if this isn't a root item in rootless mode
			if (level != -1)
			{
				populateTreeItem(this, level);
			}
		}

		public TreeItem getParentItem()
		{
			return parent;
		}

		/**
		 * @return The children
		 */
		public List<TreeItem> getChildren()
		{
			return children;
		}

		/**
		 * @return The current level
		 */
		public int getLevel()
		{
			return level;
		}

		/**
		 * @see org.apache.wicket.Component#getMarkupId()
		 */
		@Override
		public String getMarkupId()
		{
			// this is overridden to produce id that begins with id of tree
			// if the tree has set (shorter) id in markup, we can use it to
			// shorten the id of individual TreeItems
			return AbstractTree.this.getMarkupId() + "_" + getId();
		}

		/**
		 * Sets the children.
		 * 
		 * @param children
		 *            The children
		 */
		public void setChildren(List<TreeItem> children)
		{
			this.children = children;
		}

		/**
		 * Whether to render children.
		 * 
		 * @return whether to render children
		 */
		protected final boolean isRenderChildren()
		{
			return getFlag(FLAG_RENDER_CHILDREN);
		}

		/**
		 * Whether the TreeItem has any child TreeItems
		 * 
		 * @return true if there are one or more child TreeItems; false otherwise
		 */
		public boolean hasChildTreeItems()
		{
			return children != null && !children.isEmpty();
		}

		/**
		 * @see org.apache.wicket.MarkupContainer#onRender(org.apache.wicket.markup.MarkupStream)
		 */
		@Override
		protected void onRender(final MarkupStream markupStream)
		{
			// is this root and tree is in rootless mode?
			if (this == rootItem && isRootLess() == true)
			{
				// yes, write empty div with id
				// this is necessary for createElement js to work correctly
				String tagName = ((ComponentTag)markupStream.get()).getName();
				getResponse().write(
					"<" + tagName + " style=\"display:none\" id=\"" + getMarkupId() + "\"></" +
						tagName + ">");
				markupStream.skipComponent();
			}
			else
			{
				// remember current index
				final int index = markupStream.getCurrentIndex();

				// render the item
				super.onRender(markupStream);

				// should we also render children (ajax response)
				if (isRenderChildren())
				{
					// visit every child
					visitItemChildren(this, new IItemCallback()
					{
						public void visitItem(TreeItem item)
						{
							// rewind markupStream
							markupStream.setCurrentIndex(index);
							// render child
							item.onRender(markupStream);

							// go through the behaviors and invoke IBehavior.afterRender
							List<IBehavior> behaviors = item.getBehaviors();
							for (IBehavior behavior : behaviors)
							{
								behavior.afterRender(item);
							}
						}
					});
					//
				}
			}
		}

		public Object getModelObject()
		{
			return getDefaultModelObject();
		}

		@Override
		public void renderHead(final HtmlHeaderContainer container)
		{
			super.renderHead(container);

			if (isRenderChildren())
			{
				// visit every child
				visitItemChildren(this, new IItemCallback()
				{
					public void visitItem(TreeItem item)
					{
						// write header contributions from the children of item
						item.visitChildren(new Component.IVisitor<Component>()
						{
							public Object component(Component component)
							{
								if (component.isVisible())
								{
									component.renderHead(container);
									return CONTINUE_TRAVERSAL;
								}
								else
								{
									return CONTINUE_TRAVERSAL_BUT_DONT_GO_DEEPER;
								}
							}
						});
					}
				});
			}
		}

		protected final void setRenderChildren(boolean value)
		{
			setFlag(FLAG_RENDER_CHILDREN, value);
		}

		@Override
		protected void onDetach()
		{
			super.onDetach();
			Object object = getModelObject();
			if (object instanceof IDetachable)
			{
				((IDetachable)object).detach();
			}

			if (isRenderChildren())
			{
				// visit every child
				visitItemChildren(this, new IItemCallback()
				{
					public void visitItem(TreeItem item)
					{
						item.detach();
					}
				});
			}

			// children are rendered, clear the flag
			setRenderChildren(false);
		}

		@Override
		protected void onBeforeRender()
		{
			onBeforeRenderInternal();
			super.onBeforeRender();

			if (isRenderChildren())
			{
				// visit every child
				visitItemChildren(this, new IItemCallback()
				{
					public void visitItem(TreeItem item)
					{
						item.prepareForRender();
					}
				});
			}
		}

		@Override
		protected void onAfterRender()
		{
			super.onAfterRender();
			if (isRenderChildren())
			{
				// visit every child
				visitItemChildren(this, new IItemCallback()
				{
					public void visitItem(TreeItem item)
					{
						item.afterRender();
					}
				});
			}
		}

		private boolean hasParentWithChildrenMarkedToRecreation()
		{
			return getParentItem() != null &&
				(getParentItem().getChildren() == null || getParentItem().hasParentWithChildrenMarkedToRecreation());
		}
	}

	/**
	 * Components that holds tree items. This is similar to ListView, but it renders tree items in
	 * the right order.
	 */
	private class TreeItemContainer extends WebMarkupContainer
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Construct.
		 * 
		 * @param id
		 *            The component id
		 */
		public TreeItemContainer(String id)
		{
			super(id);
		}

		/**
		 * @see org.apache.wicket.MarkupContainer#remove(org.apache.wicket.Component)
		 */
		@Override
		public void remove(Component component)
		{
			// when a treeItem is removed, remove reference to it from
			// nodeToItemMAp
			if (component instanceof TreeItem)
			{
				nodeToItemMap.remove(((TreeItem)component).getModelObject());
			}
			super.remove(component);
		}

		/**
		 * renders the tree items, making sure that items are rendered in the order they should be
		 * 
		 * @param markupStream
		 *            stream
		 */
		@Override
		protected void onRender(final MarkupStream markupStream)
		{
			// Save position in markup stream
			final int markupStart = markupStream.getCurrentIndex();

			// have we rendered at least one item?
			final class Rendered
			{
				boolean rendered = false;
			}

			final Rendered rendered = new Rendered();

			// is there a root item? (non-empty tree)
			if (rootItem != null)
			{
				IItemCallback callback = new IItemCallback()
				{
					public void visitItem(TreeItem item)
					{
						// rewind markup stream
						markupStream.setCurrentIndex(markupStart);

						// render component
						item.render(markupStream);

						rendered.rendered = true;
					}
				};

				// visit item and it's children
				visitItemAndChildren(rootItem, callback);
			}

			if (rendered.rendered == false)
			{
				// tree is empty, just move the markupStream
				markupStream.skipComponent();
			}
		}
	}

	private boolean attached = false;

	/** comma separated list of ids of elements to be deleted. */
	private final AppendingStringBuffer deleteIds = new AppendingStringBuffer();

	/**
	 * whether the whole tree is dirty (so the whole tree needs to be refreshed).
	 */
	private boolean dirtyAll = false;

	/**
	 * list of dirty items. if children property of these items is null, the children will be
	 * rebuild.
	 */
	private final Set<TreeItem> dirtyItems = new HashSet<TreeItem>();

	/**
	 * list of dirty items which need the DOM structure to be created for them (added items)
	 */
	private final Set<TreeItem> dirtyItemsCreateDOM = new HashSet<TreeItem>();

	/** counter for generating unique ids of every tree item. */
	private int idCounter = 0;

	/** Component whose children are tree items. */
	private TreeItemContainer itemContainer;

	/**
	 * map that maps TreeNode to TreeItem. TreeItems only exists for TreeNodes, that are visible
	 * (their parents are not collapsed).
	 */
	private final Map<Object, TreeItem> nodeToItemMap = new HashMap<Object, TreeItem>();

	/**
	 * we need to track previous model. if the model changes, we unregister the tree from listeners
	 * of old model and register the tree as listener of new model.
	 */
	private TreeModel previousModel = null;

	/** root item of the tree. */
	private TreeItem rootItem = null;

	/** whether the tree root is shown. */
	private boolean rootLess = false;

	/** stores reference to tree state. */
	private ITreeState state;

	/**
	 * Tree constructor
	 * 
	 * @param id
	 *            The component id
	 */
	public AbstractTree(String id)
	{
		super(id);
		init();
	}

	/**
	 * Tree constructor
	 * 
	 * @param id
	 *            The component id
	 * @param model
	 *            The tree model
	 */
	public AbstractTree(String id, IModel<TreeModel> model)
	{
		super(id, model);
		init();
	}

	/** called when all nodes are collapsed. */
	public final void allNodesCollapsed()
	{
		invalidateAll();
	}

	/** called when all nodes are expanded. */
	public final void allNodesExpanded()
	{
		invalidateAll();
	}

	/**
	 * 
	 * @return model
	 */
	@SuppressWarnings("unchecked")
	public IModel<TreeModel> getModel()
	{
		return (IModel<TreeModel>)getDefaultModel();
	}

	/**
	 * @return treemodel
	 */
	public TreeModel getModelObject()
	{
		return (TreeModel)getDefaultModelObject();
	}

	/**
	 * 
	 * @param model
	 *            model
	 * @return this
	 */
	public MarkupContainer setModel(IModel<TreeModel> model)
	{
		setDefaultModel(model);
		return this;
	}

	/**
	 * 
	 * @param model
	 *            model
	 * @return this
	 */
	public MarkupContainer setModelObject(TreeModel model)
	{
		setDefaultModelObject(model);
		return this;
	}

	/**
	 * Returns the TreeState of this tree.
	 * 
	 * @return Tree state instance
	 */
	public ITreeState getTreeState()
	{
		if (state == null)
		{
			state = newTreeState();

			// add this object as listener of the state
			state.addTreeStateListener(this);
			// FIXME: Where should we remove the listener?
		}
		return state;
	}

	/**
	 * This method is called before the onAttach is called. Code here gets executed before the items
	 * have been populated.
	 */
	protected void onBeforeAttach()
	{
	}

	// This is necessary because MarkupContainer.onBeforeRender involves calling
	// beforeRender on children, which results in stack overflow when called from TreeItem
	private void onBeforeRenderInternal()
	{
		if (attached == false)
		{
			onBeforeAttach();

			checkModel();

			// Do we have to rebuild the whole tree?
			if (dirtyAll && rootItem != null)
			{
				clearAllItem();
			}
			else
			{
				// rebuild children of dirty nodes that need it
				rebuildDirty();
			}

			// is root item created? (root item is null if the items have not
			// been created yet, or the whole tree was dirty and clearAllITem
			// has been called
			if (rootItem == null)
			{
				Object rootNode = getModelObject().getRoot();
				if (rootNode != null)
				{
					if (isRootLess())
					{
						rootItem = newTreeItem(null, rootNode, -1);
					}
					else
					{
						rootItem = newTreeItem(null, rootNode, 0);
					}
					itemContainer.add(rootItem);
					buildItemChildren(rootItem);
				}
			}

			attached = true;
		}
	}

	/**
	 * Called at the beginning of the request (not ajax request, unless we are rendering the entire
	 * component)
	 */
	@Override
	public void onBeforeRender()
	{
		onBeforeRenderInternal();
		super.onBeforeRender();
	}

	/**
	 * @see org.apache.wicket.MarkupContainer#onDetach()
	 */
	@Override
	public void onDetach()
	{
		attached = false;
		super.onDetach();
		if (getTreeState() instanceof IDetachable)
		{
			((IDetachable)getTreeState()).detach();
		}
	}

	/**
	 * Call to refresh the whole tree. This should only be called when the roodNode has been
	 * replaced or the entiry tree model changed.
	 */
	public final void invalidateAll()
	{
		updated();
		dirtyAll = true;
	}

	/**
	 * @return whether the tree root is shown
	 */
	public final boolean isRootLess()
	{
		return rootLess;
	}

	/**
	 * @see org.apache.wicket.markup.html.tree.ITreeStateListener#nodeCollapsed(Object)
	 */
	public final void nodeCollapsed(Object node)
	{
		if (isNodeVisible(node) == true)
		{
			invalidateNodeWithChildren(node);
		}
	}

	/**
	 * @see org.apache.wicket.markup.html.tree.ITreeStateListener#nodeExpanded(Object)
	 */
	public final void nodeExpanded(Object node)
	{
		if (isNodeVisible(node) == true)
		{
			invalidateNodeWithChildren(node);
		}
	}

	/**
	 * @see org.apache.wicket.markup.html.tree.ITreeStateListener#nodeSelected(Object) )
	 */
	public final void nodeSelected(Object node)
	{
		if (isNodeVisible(node))
		{
			invalidateNode(node, isForceRebuildOnSelectionChange());
		}
	}

	/**
	 * @see org.apache.wicket.markup.html.tree.ITreeStateListener#nodeUnselected(Object)
	 */
	public final void nodeUnselected(Object node)
	{
		if (isNodeVisible(node))
		{
			invalidateNode(node, isForceRebuildOnSelectionChange());
		}
	}

	/**
	 * Determines whether the TreeNode needs to be rebuilt if it is selected or deselected
	 * 
	 * @return true if the node should be rebuilt after (de)selection, false otherwise
	 */
	protected boolean isForceRebuildOnSelectionChange()
	{
		return true;
	}

	/**
	 * Sets whether the root of the tree should be visible.
	 * 
	 * @param rootLess
	 *            whether the root should be visible
	 */
	public void setRootLess(boolean rootLess)
	{
		if (this.rootLess != rootLess)
		{
			this.rootLess = rootLess;
			invalidateAll();

			// if the tree is in rootless mode, make sure the root node is
			// expanded
			if (rootLess == true && getModelObject() != null)
			{
				getTreeState().expandNode(getModelObject().getRoot());
			}
		}
	}

	/**
	 * @see javax.swing.event.TreeModelListener#treeNodesChanged(javax.swing.event.TreeModelEvent)
	 */
	public final void treeNodesChanged(TreeModelEvent e)
	{
		if (dirtyAll)
		{
			return;
		}
		// has root node changed?
		if (e.getChildren() == null)
		{
			if (rootItem != null)
			{
				invalidateNode(rootItem.getModelObject(), true);
			}
		}
		else
		{
			// go through all changed nodes
			Object[] children = e.getChildren();
			if (children != null)
			{
				for (Object node : children)
				{
					if (isNodeVisible(node))
					{
						// if the nodes is visible invalidate it
						invalidateNode(node, true);
					}
				}
			}
		}
	}

	/**
	 * Marks the last but one visible child node of the given item as dirty, if give child is the
	 * last item of parent.
	 * 
	 * We need this to refresh the previous visible item in case the inserted / deleted item was
	 * last. The reason is that the line shape of previous item changes from L to |- .
	 * 
	 * @param parent
	 *            parent item
	 * @param child
	 *            child item
	 */
	private void markTheLastButOneChildDirty(TreeItem parent, TreeItem child)
	{
		if (parent.getChildren().indexOf(child) == parent.getChildren().size() - 1)
		{
			// go through the children backwards, start at the last but one
			// item
			for (int i = parent.getChildren().size() - 2; i >= 0; --i)
			{
				TreeItem item = parent.getChildren().get(i);

				// invalidate the node and it's children, so that they are
				// redrawn
				invalidateNodeWithChildren(item.getModelObject());

			}
		}
	}

	/**
	 * @see javax.swing.event.TreeModelListener#treeNodesInserted(javax.swing.event.TreeModelEvent)
	 */
	public final void treeNodesInserted(TreeModelEvent e)
	{
		if (dirtyAll)
		{
			return;
		}

		// get the parent node of inserted nodes
		Object parentNode = e.getTreePath().getLastPathComponent();
		TreeItem parentItem = nodeToItemMap.get(parentNode);

		if (parentItem != null && isNodeVisible(parentNode))
		{
			// parentNode was a leaf before this insertion event only if every one of
			// its current children is in the event's list of children
			List<?> eventChildren = Arrays.asList(e.getChildren());
			List<TreeItem> itemChildren = parentItem.getChildren();
			boolean wasLeaf = itemChildren == null || eventChildren.containsAll(itemChildren);

			if (wasLeaf)
			{
				// parentNode now has children for the first time, so we need to invalidate
				// grandparent so that parentNode's junctionLink gets rebuilt with a plus/minus link
				Object grandparentNode = getParentNode(parentNode);
				invalidateNodeWithChildren(grandparentNode);
				getTreeState().expandNode(parentNode);
			}
			else
			{
				if (isNodeExpanded(parentNode))
				{
					final int[] childIndices = e.getChildIndices();
					for (int i = 0; i < eventChildren.size(); ++i)
					{
						Object node = eventChildren.get(i);
						int index = childIndices[i];
							TreeItem item = newTreeItem(parentItem, node, parentItem.getLevel() + 1);
							itemContainer.add(item);

						if (itemChildren != null)
							{
							itemChildren.add(index, item);
								markTheLastButOneChildDirty(parentItem, item);
							}

							if (!dirtyItems.contains(item))
							{
								dirtyItems.add(item);
							}

							if (!dirtyItemsCreateDOM.contains(item) &&
								!item.hasParentWithChildrenMarkedToRecreation())
							{
								dirtyItemsCreateDOM.add(item);
							}
						}
					}
				}
			}
		}

	/**
	 * @see javax.swing.event.TreeModelListener#treeNodesRemoved(javax.swing.event.TreeModelEvent)
	 */
	public final void treeNodesRemoved(TreeModelEvent removalEvent)
	{
		if (dirtyAll)
		{
			return;
		}

		// get the parent node of deleted nodes
		Object parentNode = removalEvent.getTreePath().getLastPathComponent();
		TreeItem parentItem = nodeToItemMap.get(parentNode);

		if (parentItem != null && isNodeVisible(parentNode))
		{
			if (isNodeExpanded(parentNode))
			{
				// deleted nodes were visible; we need to delete their TreeItems
				for (Object deletedNode : removalEvent.getChildren())
				{
					TreeItem itemToDelete = nodeToItemMap.get(deletedNode);
					if (itemToDelete != null)
					{
						markTheLastButOneChildDirty(parentItem, itemToDelete);

						// remove all the deleted item's children
						visitItemChildren(itemToDelete, new IItemCallback()
						{
							public void visitItem(TreeItem item)
							{
								removeItem(item);
								getTreeState().selectNode(item.getModelObject(), false);
							}
						});

						parentItem.getChildren().remove(itemToDelete);
						removeItem(itemToDelete);
						getTreeState().selectNode(itemToDelete.getModelObject(), false);
					}
				}
			}

			if (!parentItem.hasChildTreeItems())
			{
				// rebuild parent's icon to show it no longer has children
				invalidateNode(parentNode, true);
		}

	}
	}

	/**
	 * @see javax.swing.event.TreeModelListener#treeStructureChanged(javax.swing.event.TreeModelEvent)
	 */
	public final void treeStructureChanged(TreeModelEvent e)
	{
		if (dirtyAll)
		{
			return;
		}

		// get the parent node of changed nodes
		Object node = e.getTreePath() != null ? e.getTreePath().getLastPathComponent() : null;

		// has the tree root changed?
		if (node == null || e.getTreePath().getPathCount() == 1)
		{
			invalidateAll();
		}
		else
		{
			invalidateNodeWithChildren(node);
		}
	}

	/**
	 * Convenience method that updates changed portions on tree. You can call this method during
	 * Ajax response, where calling {@link #updateTree(AjaxRequestTarget)} would be appropriate, but
	 * you don't have the AjaxRequestTarget instance. However, it is also safe to call this method
	 * outside Ajax response.
	 */
	public final void updateTree()
	{
		IRequestTarget target = getRequestCycle().getRequestTarget();
		if (target instanceof AjaxRequestTarget)
		{
			updateTree((AjaxRequestTarget)target);
		}
	}

	/**
	 * Allows to intercept adding dirty components to AjaxRequestTarget.
	 * 
	 * @param target
	 *            ajax request target
	 * @param component
	 *            component
	 */
	protected void addComponent(AjaxRequestTarget target, Component component)
	{
		target.addComponent(component);
	}

	public void onTargetRespond(AjaxRequestTarget target)
	{
		// check whether the model hasn't changed
		checkModel();

		// is the whole tree dirty
		if (dirtyAll)
		{
			// render entire tree component
			addComponent(target, this);
		}
		else
		{
			// remove DOM elements that need to be removed
			if (deleteIds.length() != 0)
			{
				String js = getElementsDeleteJavascript();

				// add the javascript to target
				target.prependJavascript(js);
			}

			// We have to repeat this as long as there are any dirty items to be
			// created.
			// The reason why we can't do this in one pass is that some of the
			// items
			// may need to be inserted after items that has not been inserted
			// yet, so we have
			// to detect those and wait until the items they depend on are
			// inserted.
			while (dirtyItemsCreateDOM.isEmpty() == false)
			{
				for (Iterator<TreeItem> i = dirtyItemsCreateDOM.iterator(); i.hasNext();)
				{
					TreeItem item = i.next();
					TreeItem parent = item.getParentItem();
					int index = parent.getChildren().indexOf(item);
					TreeItem previous;
					// we need item before this (in dom structure)

					if (index == 0)
					{
						previous = parent;
					}
					else
					{
						previous = parent.getChildren().get(index - 1);
						// get the last item of previous item subtree
						while (previous.getChildren() != null && previous.getChildren().size() > 0)
						{
							previous = previous.getChildren()
								.get(previous.getChildren().size() - 1);
						}
					}
					// check if the previous item isn't waiting to be inserted
					if (dirtyItemsCreateDOM.contains(previous) == false)
					{
						// it's already in dom, so we can use it as point of
						// insertion
						target.prependJavascript("Wicket.Tree.createElement(\"" +
							item.getMarkupId() + "\"," + "\"" + previous.getMarkupId() + "\")");

						// remove the item so we don't process it again
						i.remove();
					}
					else
					{
						// we don't do anything here, inserting this item will
						// have to wait
						// until the previous item gets inserted
					}
				}
			}

			// iterate through dirty items
			for (TreeItem item : dirtyItems)
			{
				// does the item need to rebuild children?
				if (item.getChildren() == null)
				{
					// rebuild the children
					buildItemChildren(item);

					// set flag on item so that it renders itself together with
					// it's children
					item.setRenderChildren(true);
				}

				// add the component to target
				addComponent(target, item);
			}

			// clear dirty flags
			updated();
		}
	}

	/**
	 * Updates the changed portions of the tree using given AjaxRequestTarget. Call this method if
	 * you modified the tree model during an ajax request target and you want to partially update
	 * the component on page. Make sure that the tree model has fired the proper listener functions.
	 * <p>
	 * <b>You can only call this method once in a request.</b>
	 * 
	 * @param target
	 *            Ajax request target used to send the update to the page
	 */
	public final void updateTree(final AjaxRequestTarget target)
	{
		if (target == null)
		{
			return;
		}

		target.registerRespondListener(this);
	}

	/**
	 * Returns whether the given node is expanded.
	 * 
	 * @param node
	 *            The node to inspect
	 * @return true if the node is expanded, false otherwise
	 */
	protected final boolean isNodeExpanded(Object node)
	{
		// In root less mode the root node is always expanded
		if (isRootLess() && rootItem != null && rootItem.getModelObject().equals(node))
		{
			return true;
		}

		return getTreeState().isNodeExpanded(node);
	}

	/**
	 * Creates the TreeState, which is an object where the current state of tree (which nodes are
	 * expanded / collapsed, selected, ...) is stored.
	 * 
	 * @return Tree state instance
	 */
	protected ITreeState newTreeState()
	{
		return new DefaultTreeState();
	}

	/**
	 * Called after the rendering of tree is complete. Here we clear the dirty flags.
	 */
	@Override
	protected void onAfterRender()
	{
		super.onAfterRender();
		// rendering is complete, clear all dirty flags and items
		updated();
	}

	/**
	 * This method is called after creating every TreeItem. This is the place for adding components
	 * on item (junction links, labels, icons...)
	 * 
	 * @param item
	 *            newly created tree item. The node can be obtained as item.getModelObject()
	 * 
	 * @param level
	 *            how deep the component is in tree hierarchy (0 for root item)
	 */
	protected abstract void populateTreeItem(WebMarkupContainer item, int level);

	/**
	 * Builds the children for given TreeItem. It recursively traverses children of it's TreeNode
	 * and creates TreeItem for every visible TreeNode.
	 * 
	 * @param item
	 *            The parent tree item
	 */
	private void buildItemChildren(TreeItem item)
	{
		List<TreeItem> items;

		// if the node is expanded
		if (isNodeExpanded(item.getModelObject()))
		{
			// build the items for children of the items' treenode.
			items = buildTreeItems(item, nodeChildren(item.getModelObject()), item.getLevel() + 1);
		}
		else
		{
			// it's not expanded, just set children to an empty list
			items = new ArrayList<TreeItem>(0);
		}

		item.setChildren(items);
	}

	/**
	 * Builds (recursively) TreeItems for the given Iterator of TreeNodes.
	 * 
	 * @param parent
	 *            parent item
	 * @param nodes
	 *            The nodes to build tree items for
	 * @param level
	 *            The current level
	 * @return List with new tree items
	 */
	private List<TreeItem> buildTreeItems(TreeItem parent, Iterator<Object> nodes, int level)
	{
		List<TreeItem> result = new ArrayList<TreeItem>();

		// for each node
		while (nodes.hasNext())
		{
			Object node = nodes.next();
			// create tree item
			TreeItem item = newTreeItem(parent, node, level);
			itemContainer.add(item);

			// builds it children (recursively)
			buildItemChildren(item);

			// add item to result
			result.add(item);
		}

		return result;
	}

	/**
	 * Checks whether the model has been changed, and if so unregister and register listeners.
	 */
	private void checkModel()
	{
		// find out whether the model object (the TreeModel) has been changed
		TreeModel model = getModelObject();
		if (model != previousModel)
		{
			if (previousModel != null)
			{
				previousModel.removeTreeModelListener(this);
			}

			previousModel = model;

			if (model != null)
			{
				model.addTreeModelListener(this);
			}
			// model has been changed, redraw whole tree
			invalidateAll();
		}
	}

	/**
	 * Removes all TreeItem components.
	 */
	private void clearAllItem()
	{
		visitItemAndChildren(rootItem, new IItemCallback()
		{
			public void visitItem(TreeItem item)
			{
				item.remove();
			}
		});
		rootItem = null;
	}

	/**
	 * Returns the javascript used to delete removed elements.
	 * 
	 * @return The javascript
	 */
	private String getElementsDeleteJavascript()
	{
		// build the javascript call
		final AppendingStringBuffer buffer = new AppendingStringBuffer(100);

		buffer.append("Wicket.Tree.removeNodes(\"");

		// first parameter is the markup id of tree (will be used as prefix to
		// build ids of child items
		buffer.append(getMarkupId() + "_\",[");

		// append the ids of elements to be deleted
		buffer.append(deleteIds);

		// does the buffer end if ','?
		if (buffer.endsWith(","))
		{
			// it does, trim it
			buffer.setLength(buffer.length() - 1);
		}

		buffer.append("]);");

		return buffer.toString();
	}

	//
	// State and Model callbacks
	//

	/**
	 * returns the short version of item id (just the number part).
	 * 
	 * @param item
	 *            The tree item
	 * @return The id
	 */
	private String getShortItemId(TreeItem item)
	{
		// show much of component id can we skip? (to minimize the length of
		// javascript being sent)
		final int skip = getMarkupId().length() + 1; // the length of id of
		// tree and '_'.
		return item.getMarkupId().substring(skip);
	}

	private final static ResourceReference JAVASCRIPT = new JavascriptResourceReference(
		AbstractTree.class, "res/tree.js");

	/**
	 * Initialize the component.
	 */
	private void init()
	{
		setVersioned(false);

		// we need id when we are replacing the whole tree
		setOutputMarkupId(true);

		// create container for tree items
		itemContainer = new TreeItemContainer("i");
		add(itemContainer);

		add(JavascriptPackageResource.getHeaderContribution(JAVASCRIPT));

		checkModel();
	}

	/**
	 * INTERNAL
	 * 
	 * @param node
	 *            node
	 */
	public final void markNodeDirty(Object node)
	{
		invalidateNode(node, false);
	}

	/**
	 * INTERNAL
	 * 
	 * @param node
	 *            node
	 */
	public final void markNodeChildrenDirty(Object node)
	{
		TreeItem item = nodeToItemMap.get(node);
		if (item != null)
		{
			visitItemChildren(item, new IItemCallback()
			{
				public void visitItem(TreeItem item)
				{
					invalidateNode(item.getModelObject(), false);
				}
			});
		}
	}

	/**
	 * Invalidates single node (without children). On the next render, this node will be updated.
	 * Node will not be rebuilt, unless forceRebuild is true.
	 * 
	 * @param node
	 *            The node to invalidate
	 * @param forceRebuild
	 *            force rebuild of node
	 */
	private void invalidateNode(Object node, boolean forceRebuild)
	{
		if (dirtyAll == false)
		{
			// get item for this node
			TreeItem item = nodeToItemMap.get(node);

			if (item != null)
			{
				boolean createDOM = false;

				if (forceRebuild)
				{
					// recreate the item
					int level = item.getLevel();
					List<TreeItem> children = item.getChildren();
					String id = item.getId();

					// store the parent of old item
					TreeItem parent = item.getParentItem();

					// if the old item has a parent, store it's index
					int index = parent != null ? parent.getChildren().indexOf(item) : -1;

					createDOM = dirtyItemsCreateDOM.contains(item);

					dirtyItems.remove(item);
					dirtyItemsCreateDOM.remove(item);

					item.remove();

					item = newTreeItem(parent, node, level, id);
					itemContainer.add(item);

					item.setChildren(children);

					// was the item an root item?
					if (parent == null)
					{
						rootItem = item;
					}
					else
					{
						parent.getChildren().set(index, item);
					}
				}

				if (!dirtyItems.contains(item))
				{
					dirtyItems.add(item);
				}

				if (createDOM && !dirtyItemsCreateDOM.contains(item))
				{
					dirtyItemsCreateDOM.add(item);
				}
			}
		}
	}

	/**
	 * Invalidates node and it's children. On the next render, the node and children will be
	 * updated. Node children will be rebuilt.
	 * 
	 * @param node
	 *            The node to invalidate
	 */
	private void invalidateNodeWithChildren(Object node)
	{
		if (dirtyAll == false)
		{
			// get item for this node
			TreeItem item = nodeToItemMap.get(node);

			// is the item visible?
			if (item != null)
			{
				// go though item children and remove every one of them
				visitItemChildren(item, new IItemCallback()
				{
					public void visitItem(TreeItem item)
					{
						removeItem(item);
					}
				});

				// set children to null so that they get rebuild
				item.setChildren(null);

				if (!dirtyItems.contains(item))
				{
					// add item to dirty items
					dirtyItems.add(item);
				}
			}
		}
	}

	/**
	 * Returns whether the given node is visible, e.g. all it's parents are expanded.
	 * 
	 * @param node
	 *            The node to inspect
	 * @return true if the node is visible, false otherwise
	 */
	private boolean isNodeVisible(Object node)
	{
		if (node == null)
		{
			return false;
		}
		Object parent = getParentNode(node);
		while (parent != null)
		{
			if (isNodeExpanded(parent) == false)
			{
				return false;
			}
			parent = getParentNode(parent);
		}
		return true;
	}

	/**
	 * Returns parent node of given node.
	 * 
	 * @param node
	 *            node
	 * @return parent node
	 */
	public Object getParentNode(Object node)
	{
		TreeItem item = nodeToItemMap.get(node);
		if (item == null)
		{
			return null;
		}
		else
		{
			TreeItem parent = item.getParentItem();
			return parent == null ? null : parent.getModelObject();
		}
	}

	/**
	 * Creates a tree item for given node.
	 * 
	 * @param parent
	 *            parent tree item
	 * @param node
	 *            The tree node
	 * @param level
	 *            The level
	 * @return The new tree item
	 */
	private TreeItem newTreeItem(TreeItem parent, Object node, int level)
	{
		return new TreeItem(parent, "" + idCounter++, node, level);
	}

	/**
	 * Creates a tree item for given node with specified id.
	 * 
	 * @param parent
	 *            parent tree item
	 * @param node
	 *            The tree node
	 * @param level
	 *            The level
	 * @param id
	 *            the component id
	 * @return The new tree item
	 */
	private TreeItem newTreeItem(TreeItem parent, Object node, int level, String id)
	{
		return new TreeItem(parent, id, node, level);
	}

	/**
	 * Return the representation of node children as Iterator interface.
	 * 
	 * @param node
	 *            The tree node
	 * @return iterable presentation of node children
	 */
	public final Iterator<Object> nodeChildren(Object node)
	{
		TreeModel model = getTreeModel();
		int count = model.getChildCount(node);
		List<Object> nodes = new ArrayList<Object>(count);
		for (int i = 0; i < count; ++i)
		{
			nodes.add(model.getChild(node, i));
		}
		return nodes.iterator();
	}

	/**
	 * @param parent
	 *            parent node
	 * @param index
	 *            index
	 * @return child
	 */
	public final Object getChildAt(Object parent, int index)
	{
		return getTreeModel().getChild(parent, index);
	}

	/**
	 * 
	 * @param node
	 *            node
	 * @return boolean
	 */
	public final boolean isLeaf(Object node)
	{
		return getTreeModel().isLeaf(node);
	}

	/**
	 * @param parent
	 *            parent node
	 * @return child count
	 */
	public final int getChildCount(Object parent)
	{
		return getTreeModel().getChildCount(parent);
	}

	private TreeModel getTreeModel()
	{
		return getModelObject();
	}

	/**
	 * Rebuilds children of every item in dirtyItems that needs it. This method is called for
	 * non-partial update.
	 */
	private void rebuildDirty()
	{
		// go through dirty items
		for (TreeItem item : dirtyItems)
		{
			// item children need to be rebuilt
			if (item.getChildren() == null)
			{
				buildItemChildren(item);
			}
		}
	}

	/**
	 * Removes the item, appends it's id to deleteIds. This is called when a items parent is being
	 * deleted or rebuilt.
	 * 
	 * @param item
	 *            The item to remove
	 */
	private void removeItem(TreeItem item)
	{
		// even if the item is dirty it's no longer necessary to update id
		dirtyItems.remove(item);

		// if the item was about to be created
		if (dirtyItemsCreateDOM.contains(item))
		{
			// we needed to create DOM element, we no longer do
			dirtyItemsCreateDOM.remove(item);
		}
		else
		{
			// add items id (it's short version) to ids of DOM elements that
			// will be
			// removed
			deleteIds.append(getShortItemId(item));
			deleteIds.append(",");
		}

		if (item.getParent() != null)
		{
			// remove the id
			// note that this doesn't update item's parent's children list
			item.remove();
		}
	}

	/**
	 * Calls after the tree has been rendered. Clears all dirty flags.
	 */
	private void updated()
	{
		dirtyAll = false;
		dirtyItems.clear();
		dirtyItemsCreateDOM.clear();
		deleteIds.clear(); // FIXME: Recreate it to save some space?
	}

	/**
	 * Call the callback#visitItem method for the given item and all it's children.
	 * 
	 * @param item
	 *            The tree item
	 * @param callback
	 *            item call back
	 */
	private void visitItemAndChildren(TreeItem item, IItemCallback callback)
	{
		callback.visitItem(item);
		visitItemChildren(item, callback);
	}

	/**
	 * Call the callback#visitItem method for every child of given item.
	 * 
	 * @param item
	 *            The tree item
	 * @param callback
	 *            The callback
	 */
	private void visitItemChildren(TreeItem item, IItemCallback callback)
	{
		if (item.getChildren() != null)
		{
			for (TreeItem child : item.getChildren())
			{
				visitItemAndChildren(child, callback);
			}
		}
	}

	/**
	 * Returns the component associated with given node, or null, if node is not visible. This is
	 * useful in situations when you want to touch the node element in html.
	 * 
	 * @param node
	 *            Tree node
	 * @return Component associated with given node, or null if node is not visible.
	 */
	public Component getNodeComponent(Object node)
	{
		return nodeToItemMap.get(node);
	}
}
