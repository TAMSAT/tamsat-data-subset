// Calls to the server to determine available time lists
window.onload = function() {
    // Add some listeners to make bounding box obey simple rules
    var minLat = document.getElementById('minLat');
    var maxLat = document.getElementById('maxLat');
    var minLon = document.getElementById('minLon');
    var maxLon = document.getElementById('maxLon');
    minLat.addEventListener('change', function(e) {
        if (parseFloat(minLat.value) > parseFloat(maxLat.value)) {
            maxLat.value = 1 + parseFloat(minLat.value);
        }
    });
    maxLat.addEventListener('change', function(e) {
        if (parseFloat(minLat.value) > parseFloat(maxLat.value)) {
            minLat.value = parseFloat(maxLat.value) - 1;
        }
    });
    minLon.addEventListener('change', function(e) {
        if (parseFloat(minLon.value) > parseFloat(maxLon.value)) {
            maxLon.value = 1 + parseFloat(minLon.value);
        }
    });
    maxLon.addEventListener('change', function(e) {
        if (parseFloat(minLon.value) > parseFloat(maxLon.value)) {
            minLon.value = parseFloat(maxLon.value) - 1;
        }
    });


    var xhr = new XMLHttpRequest();
    xhr.open("GET", "data?REQUEST=GETTIMES&DATASET=tamsat", true);
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
                    if (startSel.selectedIndex > endSel.selectedIndex) {
                        endSel.selectedIndex = startSel.selectedIndex;
                    }
                });

                // Ensure that start time cannot be later than end time
                endSel.addEventListener('change', function(e) {
                    if (startSel.selectedIndex > endSel.selectedIndex) {
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

function validateForm() {
    if (!document.getElementById('email').value) {
        window.alert('You must enter an email address');
        return false;
    }
}
