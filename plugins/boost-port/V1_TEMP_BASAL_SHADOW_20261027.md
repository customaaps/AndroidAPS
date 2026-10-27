# V1 setTempBasal offline adaptation

This patch isolates the archived V1 temp-basal branch selection and max basal bounding as read-only, caller-fed diagnostic data. The result is a finding and comparison scalar only, not an APSResult, pump command, or treatment suggestion. Tests are staged but unexecuted; commit contains [skip ci]. Original Dmitry dose logic and ISF/Autosens ordering are untouched. Full V1 determine_basal port and independent safety validation remain outstanding.
