// Needs to:
//
// Validate input, then use: <form name="myForm" action="/action_page.php" onsubmit="return validateForm()" method="post">

// Calls to the server to determine available time lists
window.onload = function() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "http://localhost:8080/tamsat-subset-server/data?REQUEST=GETTIMES&DATASET=tamsat", true);
    // xhr.open("GET", "data?REQUEST=GETTIMES&DATASET=tamsat", true);
    xhr.onload = function(e) {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                var startEndTimes = JSON.parse(xhr.responseText);
                var current = new Date(Date.parse(startEndTimes.starttime));
                var end = new Date(Date.parse(startEndTimes.endtime));

                var startSel = document.getElementById('startSelect');
                var endSel = document.getElementById('endSelect');
                // Populate the drop-down lists with all available dates
                while (current <= end) {
                    startSel.appendChild(new Option(current.getDate() + '/' + (current.getMonth() + 1) + '/' + current.getFullYear(), current.toISOString()));

                    endSel.appendChild(new Option(current.getDate() + '/' + (current.getMonth() + 1) + '/' + current.getFullYear(), current.toISOString()));

                    current.setDate(current.getDate() + 1);
                }

                // Set the end time to the final available
                // TODO may want this e.g. start + 1 month or similar
                endSel.selectedIndex = endSel.options.length - 1;

                // Ensure that start time cannot be later than end time
                startSel.addEventListener('change', function(e) {
                    if(startSel.selectedIndex > endSel.selectedIndex) {
                        endSel.selectedIndex = startSel.selectedIndex;
                    }
                });

                // Ensure that start time cannot be later than end time
                endSel.addEventListener('change', function(e) {
                    if(startSel.selectedIndex > endSel.selectedIndex) {
                        startSel.selectedIndex = endSel.selectedIndex;
                    }
                });
            } else {
                console.error(xhr.statusText);
            }
        }
    };
    xhr.onerror = function(e) {
        console.error(xhr.statusText);
    };
    xhr.send(null);
}

// Gets called when radio button denoting data type changes.
// This shows / hides the appropriate spatial selector
function datatypeSelected(value) {
    if (value == 'point') {
        document.getElementById('pointSelection').style.display = 'block';
        document.getElementById('regionSelection').style.display = 'none';
    } else {
        document.getElementById('pointSelection').style.display = 'none';
        document.getElementById('regionSelection').style.display = 'block';
    }
}
