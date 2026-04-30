package app.organicmaps.editor;

import android.text.TextUtils;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import app.organicmaps.R;
import app.organicmaps.editor.data.TimeFormatUtils;
import app.organicmaps.sdk.editor.OpeningHours;
import app.organicmaps.sdk.editor.data.HoursMinutes;
import app.organicmaps.sdk.editor.data.Timetable;
import app.organicmaps.util.UiUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleTimetableAdapter extends RecyclerView.Adapter<SimpleTimetableAdapter.ViewHolder>
{
  @NonNull
  private final HoursMinutesPickerFragment.OnPickListener mListener;
  @Nullable
  private String mTimetables;
  private final List<Timetable> mItems = new ArrayList<>();

  public SimpleTimetableAdapter(@NonNull HoursMinutesPickerFragment.OnPickListener listener)
  {
    mListener = listener;
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    return new ViewHolder(LayoutInflater.from(parent.getContext())
                                        .inflate(R.layout.item_timetable, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position)
  {
    holder.bind(mItems.get(position));
  }

  @Override
  public int getItemCount()
  {
    return mItems.size();
  }

  public void setTimetables(@Nullable String timetables)
  {
    mTimetables = timetables;
    mItems.clear();

    final Timetable[] parsedTimetables = TextUtils.isEmpty(timetables) ? null : OpeningHours.nativeTimetablesFromString(timetables);
    if (parsedTimetables != null)
      Collections.addAll(mItems, parsedTimetables);

    notifyDataSetChanged();
  }

  @Nullable
  public String getTimetables()
  {
    return mTimetables;
  }

  public void setItems(List<Timetable> items)
  {
    mItems.clear();
    mItems.addAll(items);
    notifyDataSetChanged();
  }

  public List<Timetable> getItems()
  {
    return Collections.unmodifiableList(mItems);
  }

  public void onHoursMinutesPicked(HoursMinutes from, HoursMinutes to, int id)
  {
    if (mListener != null)
      mListener.onHoursMinutesPicked(from, to, id);
  }

  class ViewHolder extends RecyclerView.ViewHolder
  {
    private final SparseArray<CheckBox> days = new SparseArray<>();
    private final View allday;
    private final View schedule;
    private final TextView timeOpen;
    private final TextView timeClose;
    private final View timeOpenHost;
    private final View timeCloseHost;

    ViewHolder(@NonNull View itemView)
    {
      super(itemView);
      addDay(1, R.id.day1);
      addDay(2, R.id.day2);
      addDay(3, R.id.day3);
      addDay(4, R.id.day4);
      addDay(5, R.id.day5);
      addDay(6, R.id.day6);
      addDay(7, R.id.day7);

      allday = itemView.findViewById(R.id.allday);
      schedule = itemView.findViewById(R.id.schedule);
      timeOpen = itemView.findViewById(R.id.tv__time_open);
      timeClose = itemView.findViewById(R.id.tv__time_close);
      timeOpenHost = itemView.findViewById(R.id.time_open);
      timeCloseHost = itemView.findViewById(R.id.time_close);
    }

    private void addDay(@IntRange(from = 1, to = 7) final int dayIndex, @IdRes int id)
    {
      final View day = itemView.findViewById(id);
      // Use localized IDs or unique IDs
      final CheckBox checkBox;
      final TextView textView;
      
      if (dayIndex == 1) {
          checkBox = day.findViewById(R.id.chb__day);
          textView = day.findViewById(R.id.tv__day);
      } else {
          // Find by generic ID if they are still duplicate, or by unique if updated
          // For now, I'll use the unique IDs I created in the XML
          int cbId = itemView.getResources().getIdentifier("chb__day" + (dayIndex > 1 ? dayIndex : ""), "id", itemView.getContext().getPackageName());
          int tvId = itemView.getResources().getIdentifier("tv__day" + (dayIndex > 1 ? dayIndex : ""), "id", itemView.getContext().getPackageName());
          checkBox = day.findViewById(cbId);
          textView = day.findViewById(tvId);
      }
      
      checkBox.setTag(dayIndex);
      days.put(dayIndex, checkBox);
      day.setOnClickListener(v -> checkBox.toggle());
      textView.setText(TimeFormatUtils.formatShortWeekday(dayIndex));
    }

    void bind(Timetable item)
    {
      for (int dayIndex = 1; dayIndex <= 7; dayIndex++)
        days.get(dayIndex).setChecked(item.containsWeekday(dayIndex));

      if (item.isFullday)
      {
        allday.setVisibility(View.VISIBLE);
        schedule.setVisibility(View.GONE);
      }
      else
      {
        allday.setVisibility(View.GONE);
        schedule.setVisibility(View.VISIBLE);
        timeOpen.setText(item.workingTimespan.start.toString());
        timeClose.setText(item.workingTimespan.end.toString());
      }
    }
  }
}
