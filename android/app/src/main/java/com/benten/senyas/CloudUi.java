package com.benten.senyas;

import android.view.View;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskExecutors;
import java.util.function.Consumer;

/** Binds a cloud write to a screen without treating a queued/offline write as success. */
final class CloudUi {
    private CloudUi() { }

    static <T> void run(Fragment owner, View trigger, Task<T> task, Consumer<T> confirmed) {
        if (owner == null || trigger == null || task == null || !owner.isAdded() || owner.getView() == null) return;
        final View expectedView = owner.getView();
        final String accountId = Repo.accountId();
        final int role = Repo.accountRole();
        trigger.setEnabled(false);
        task.addOnCompleteListener(TaskExecutors.MAIN_THREAD, result -> {
            if (trigger.isAttachedToWindow()) trigger.setEnabled(true);
            boolean stillCurrent = owner.isAdded() && owner.getView() == expectedView
                    && accountId.equals(Repo.accountId()) && role == Repo.accountRole();
            if (!result.isSuccessful()) {
                if (stillCurrent) Toast.makeText(expectedView.getContext(),
                        "Could not save the shared update. Check your connection and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            if (stillCurrent) confirmed.accept(result.getResult());
        });
    }
}
