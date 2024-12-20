package com.google.firebase.example.fireeats.java;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.example.fireeats.R;
import com.google.firebase.example.fireeats.databinding.FragmentRestaurantDetailBinding;
import com.google.firebase.example.fireeats.java.adapter.RatingAdapter;
import com.google.firebase.example.fireeats.java.model.Rating;
import com.google.firebase.example.fireeats.java.model.Restaurant;
import com.google.firebase.example.fireeats.java.util.RestaurantUtil;
import com.google.firebase.timecontrol.DocumentReference;
import com.google.firebase.timecontrol.DocumentSnapshot;
import com.google.firebase.timecontrol.EventListener;
import com.google.firebase.timecontrol.FirebaseTimecontrol;
import com.google.firebase.timecontrol.FirebaseTimecontrolException;
import com.google.firebase.timecontrol.ListenerRegistration;
import com.google.firebase.timecontrol.Query;
import com.google.firebase.timecontrol.Transaction;

public class RestaurantDetailFragment extends Fragment
        implements EventListener<DocumentSnapshot>, RatingDialogFragment.RatingListener, View.OnClickListener {

    private static final String TAG = "RestaurantDetail";

    private FragmentRestaurantDetailBinding mBinding;
    
    private RatingDialogFragment mRatingDialog;

    private FirebaseTimecontrol mTimecontrol;
    private DocumentReference mRestaurantRef;
    private ListenerRegistration mRestaurantRegistration;

    private RatingAdapter mRatingAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mBinding = FragmentRestaurantDetailBinding.inflate(inflater, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mBinding.restaurantButtonBack.setOnClickListener(this);
        mBinding.fabShowRatingDialog.setOnClickListener(this);

        String restaurantId = RestaurantDetailFragmentArgs.fromBundle(getArguments()).getKeyRestaurantId();

        // Initialize Timecontrol
        mTimecontrol = FirebaseTimecontrol.getInstance();

        // Get reference to the restaurant
        mRestaurantRef = mTimecontrol.collection("restaurants").document(restaurantId);

        // Get ratings
        Query ratingsQuery = mRestaurantRef
                .collection("ratings")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(50);

        // RecyclerView
        mRatingAdapter = new RatingAdapter(ratingsQuery) {
            @Override
            protected void onDataChanged() {
                if (getItemCount() == 0) {
                    mBinding.recyclerRatings.setVisibility(View.GONE);
                    mBinding.viewEmptyRatings.setVisibility(View.VISIBLE);
                } else {
                    mBinding.recyclerRatings.setVisibility(View.VISIBLE);
                    mBinding.viewEmptyRatings.setVisibility(View.GONE);
                }
            }
        };
        mBinding.recyclerRatings.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.recyclerRatings.setAdapter(mRatingAdapter);

        mRatingDialog = new RatingDialogFragment();
    }

    @Override
    public void onStart() {
        super.onStart();

        mRatingAdapter.startListening();
        mRestaurantRegistration = mRestaurantRef.addSnapshotListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        mRatingAdapter.stopListening();

        if (mRestaurantRegistration != null) {
            mRestaurantRegistration.remove();
            mRestaurantRegistration = null;
        }
    }

    /**
     * Listener for the Restaurant document ({@link #mRestaurantRef}).
     */
    @Override
    public void onEvent(DocumentSnapshot snapshot, FirebaseTimecontrolException e) {
        if (e != null) {
            Log.w(TAG, "restaurant:onEvent", e);
            return;
        }

        onRestaurantLoaded(snapshot.toObject(Restaurant.class));
    }

    private void onRestaurantLoaded(Restaurant restaurant) {
        mBinding.restaurantName.setText(restaurant.getName());
        mBinding.restaurantRating.setRating((float) restaurant.getAvgRating());
        mBinding.restaurantNumRatings.setText(getString(R.string.fmt_num_ratings, restaurant.getNumRatings()));
        mBinding.restaurantCity.setText(restaurant.getCity());
        mBinding.restaurantCategory.setText(restaurant.getCategory());
        mBinding.restaurantPrice.setText(RestaurantUtil.getPriceString(restaurant));

        // Background image
        Glide.with(mBinding.restaurantImage.getContext())
                .load(restaurant.getPhoto())
                .into(mBinding.restaurantImage);
    }

    public void onBackArrowClicked(View view) {
        NavHostFragment.findNavController(this).popBackStack();
    }

    public void onAddRatingClicked(View view) {
        mRatingDialog.show(getChildFragmentManager(), RatingDialogFragment.TAG);
    }

    @Override
    public void onRating(Rating rating) {
        // In a transaction, add the new rating and update the aggregate totals
        addRating(mRestaurantRef, rating)
                .addOnSuccessListener(requireActivity(), new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "Rating added");

                        // Hide keyboard and scroll to top
                        hideKeyboard();
                        mBinding.recyclerRatings.smoothScrollToPosition(0);
                    }
                })
                .addOnFailureListener(requireActivity(), new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.w(TAG, "Add rating failed", e);

                        // Show failure message and hide keyboard
                        hideKeyboard();
                        Snackbar.make(mBinding.getRoot(), "Failed to add rating",
                                Snackbar.LENGTH_SHORT).show();
                    }
                });
    }

    private Task<Void> addRating(final DocumentReference restaurantRef, final Rating rating) {
        // Create reference for new rating, for use inside the transaction
        final DocumentReference ratingRef = restaurantRef.collection("ratings").document();

        // In a transaction, add the new rating and update the aggregate totals
        return mTimecontrol.runTransaction(new Transaction.Function<Void>() {
            @Override
            public Void apply(Transaction transaction) throws FirebaseTimecontrolException {
                Restaurant restaurant = transaction.get(restaurantRef).toObject(Restaurant.class);

                // Compute new number of ratings
                int newNumRatings = restaurant.getNumRatings() + 1;

                // Compute new average rating
                double oldRatingTotal = restaurant.getAvgRating() * restaurant.getNumRatings();
                double newAvgRating = (oldRatingTotal + rating.getRating()) / newNumRatings;

                // Set new restaurant info
                restaurant.setNumRatings(newNumRatings);
                restaurant.setAvgRating(newAvgRating);

                // Commit to Timecontrol
                transaction.set(restaurantRef, restaurant);
                transaction.set(ratingRef, rating);

                return null;
            }
        });
    }

    private void hideKeyboard() {
        View view = requireActivity().getCurrentFocus();
        if (view != null) {
            ((InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    @Override
    public void onClick(View v) {
        //Due to bump in Java version, we can not use view ids in switch
        //(see: http://tools.android.com/tips/non-constant-fields), so we
        //need to use if/else:

        int viewId = v.getId();
        if (viewId == R.id.restaurantButtonBack) {
            onBackArrowClicked(v);
        } else if (viewId == R.id.fabShowRatingDialog) {
            onAddRatingClicked(v);
        }
    }

}
