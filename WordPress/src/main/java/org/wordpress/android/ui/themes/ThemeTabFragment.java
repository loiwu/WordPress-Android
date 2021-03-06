package org.wordpress.android.ui.themes;

import android.app.Activity;
import android.app.Fragment;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.GridView;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.android.volley.toolbox.ImageLoader.ImageListener;
import com.android.volley.toolbox.NetworkImageView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.ui.themes.ThemeTabAdapter.ScreenshotHolder;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ptr.SwipeToRefreshHelper;
import org.wordpress.android.util.ptr.SwipeToRefreshHelper.RefreshListener;

/**
 * A fragment display the themes on a grid view.
 */
public class ThemeTabFragment extends Fragment implements OnItemClickListener, RecyclerListener {
    public enum ThemeSortType {
        TRENDING("Trending"),
        NEWEST("Newest"),
        POPULAR("Popular");

        private String mTitle;

        private ThemeSortType(String title) {
            mTitle = title;
        }

        public String getTitle() {
            return mTitle;
        }

        public static ThemeSortType getTheme(int position) {
            if (position < ThemeSortType.values().length)
                return ThemeSortType.values()[position];
            else
                return TRENDING;
        }
    }

    public interface ThemeTabFragmentCallback {
        public void onThemeSelected(String themeId);
    }

    protected static final String ARGS_SORT = "ARGS_SORT";
    protected static final String ARGS_PAGE = "ARGS_PAGE";
    protected static final String BUNDLE_SCROLL_POSTION = "BUNDLE_SCROLL_POSTION";

    protected GridView mGridView;
    protected TextView mEmptyView;
    protected TextView mNoResultText;
    protected ThemeTabAdapter mAdapter;
    protected ThemeTabFragmentCallback mCallback;
    protected int mSavedScrollPosition = 0;
    private boolean mShouldRefreshOnStart;
    private SwipeToRefreshHelper mSwipeToRefreshHelper;

    public static ThemeTabFragment newInstance(ThemeSortType sort, int page) {
        ThemeTabFragment fragment = new ThemeTabFragment();
        Bundle args = new Bundle();
        args.putInt(ARGS_SORT, sort.ordinal());
        args.putInt(ARGS_PAGE, page);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mCallback = (ThemeTabFragmentCallback) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement ThemeTabFragmentCallback");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.theme_tab_fragment, container, false);

        setRetainInstance(true);

        mNoResultText = (TextView) view.findViewById(R.id.theme_no_search_result_text);
        mEmptyView = (TextView) view.findViewById(R.id.text_empty);
        mGridView = (GridView) view.findViewById(R.id.theme_gridview);
        mGridView.setRecyclerListener(this);

        // swipe to refresh setup but not for the search view
        if (!(this instanceof ThemeSearchFragment)) {
            mSwipeToRefreshHelper = new SwipeToRefreshHelper(getActivity(), (SwipeRefreshLayout) view.findViewById(
                    R.id.ptr_layout), new RefreshListener() {
                @Override
                public void onRefreshStarted() {
                    if (!isAdded()) {
                        return;
                    }
                    if (!NetworkUtils.checkConnection(getActivity())) {
                        mSwipeToRefreshHelper.setRefreshing(false);
                        mEmptyView.setText(R.string.no_network_title);
                        return;
                    }
                    if (getActivity() instanceof ThemeBrowserActivity) {
                        ((ThemeBrowserActivity) getActivity()).fetchThemes(getArguments().getInt(ARGS_PAGE));
                    }
                }
            });
            mSwipeToRefreshHelper.setRefreshing(mShouldRefreshOnStart);
        }
        restoreState(savedInstanceState);
        return view;
    }

    public void setRefreshing(boolean refreshing) {
        mShouldRefreshOnStart = refreshing;
        if (mSwipeToRefreshHelper != null) {
            mSwipeToRefreshHelper.setRefreshing(refreshing);
            if (!refreshing) {
                refreshView();
            }
        }
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mSavedScrollPosition = savedInstanceState.getInt(BUNDLE_SCROLL_POSTION, 0);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Cursor cursor = fetchThemes(getThemeSortType());
        if (cursor == null) {
            return;
        }
        mAdapter = new ThemeTabAdapter(getActivity(), cursor, false);
        setEmptyViewVisible(mAdapter.getCount() == 0);
        mGridView.setAdapter(mAdapter);
        mGridView.setOnItemClickListener(this);
        mGridView.setSelection(mSavedScrollPosition);
    }

    private void setEmptyViewVisible(boolean visible) {
        if (getView() == null || !isAdded()) {
            return;
        }
        mEmptyView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible && !NetworkUtils.isNetworkAvailable(getActivity())) {
            mEmptyView.setText(R.string.no_network_title);
        }
    }

    public void setEmptyViewText(int stringId) {
        mEmptyView.setText(stringId);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mGridView != null)
            outState.putInt(BUNDLE_SCROLL_POSTION, mGridView.getFirstVisiblePosition());
    }

    private ThemeSortType getThemeSortType() {
        int sortType = ThemeSortType.TRENDING.ordinal();
        if (getArguments().containsKey(ARGS_SORT))  {
            sortType = getArguments().getInt(ARGS_SORT);
        }

        return ThemeSortType.getTheme(sortType);
    }

    /**
     * Fetch themes for a given ThemeSortType.
     *
     * @return a db Cursor or null if current blog is null
     */
    private Cursor fetchThemes(ThemeSortType themeSortType) {
        if (WordPress.getCurrentBlog() == null) {
            return null;
        }
        String blogId = String.valueOf(WordPress.getCurrentBlog().getRemoteBlogId());
        switch (themeSortType) {
            case POPULAR:
                return WordPress.wpDB.getThemesPopularity(blogId);
            case NEWEST:
                return WordPress.wpDB.getThemesNewest(blogId);
            case TRENDING:
            default:
                return WordPress.wpDB.getThemesTrending(blogId);
        }
    }

    private void refreshView() {
        Cursor cursor = fetchThemes(getThemeSortType());
        if (cursor == null) {
            return;
        }
        if (mAdapter == null) {
            mAdapter = new ThemeTabAdapter(getActivity(), cursor, false);
        }
        if (mNoResultText.isShown()) {
            mNoResultText.setVisibility(View.GONE);
        }
        mAdapter.changeCursor(cursor);
        setEmptyViewVisible(mAdapter.getCount() == 0);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Cursor cursor = ((ThemeTabAdapter) parent.getAdapter()).getCursor();
        String themeId = cursor.getString(cursor.getColumnIndex("themeId"));
        mCallback.onThemeSelected(themeId);
    }

    @Override
    public void onMovedToScrapHeap(View view) {
        // cancel image fetch requests if the view has been moved to recycler.

        NetworkImageView niv = (NetworkImageView) view.findViewById(R.id.theme_grid_item_image);
        if (niv != null) {
            // this tag is set in the ThemeTabAdapter class
            ScreenshotHolder tag =  (ScreenshotHolder) niv.getTag();
            if (tag != null && tag.requestURL != null) {
                // need a listener to cancel request, even if the listener does nothing
                ImageContainer container = WordPress.imageLoader.get(tag.requestURL, new ImageListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) { }

                    @Override
                    public void onResponse(ImageContainer response, boolean isImmediate) { }

                });
                container.cancelRequest();
            }
        }
    }
}
