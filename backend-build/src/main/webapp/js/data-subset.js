// Calls to the server to determine available time lists
window.onload = function() {
    // Add some listeners to make bounding box obey simple rules
    var minLat = document.getElementById('minLat');
    var maxLat = document.getElementById('maxLat');
    var minLon = document.getElementById('minLon');
    var maxLon = document.getElementById('maxLon');

    // Basic sanity check for min/max limits
    // WHY DON'T BROWSERS DO THIS?
    var checkLimits = function(s) {
        if (parseFloat(s.value) > s.max) {
            s.value = s.max;
        } else if (parseFloat(s.value) < s.min) {
            s.value = s.min;
        }
    }

    minLat.addEventListener('change', function(e) {
        checkLimits(minLat);
        if (parseFloat(minLat.value) > parseFloat(maxLat.value)) {
            maxLat.value = 1 + parseFloat(minLat.value);
        }
    });
    maxLat.addEventListener('change', function(e) {
        checkLimits(maxLat);
        if (parseFloat(minLat.value) > parseFloat(maxLat.value)) {
            minLat.value = parseFloat(maxLat.value) - 1;
        }
    });
    minLon.addEventListener('change', function(e) {
        checkLimits(minLon);
        if (parseFloat(minLon.value) > parseFloat(maxLon.value)) {
            maxLon.value = 1 + parseFloat(minLon.value);
        }
    });
    maxLon.addEventListener('change', function(e) {
        checkLimits(maxLon);
        if (parseFloat(minLon.value) > parseFloat(maxLon.value)) {
            minLon.value = parseFloat(maxLon.value) - 1;
        }
    });

    // and listeners for the min/max limits for the point lat/lon boxes
    var lat = document.getElementById('lat');
    lat.addEventListener('change', function(e) {
        checkLimits(lat);
    })
    var lon = document.getElementById('lon');
    lat.addEventListener('change', function(e) {
        checkLimits(lon);
    })

    populateDatasets();
    populateCountries();

    // This is normally handled by the fact that datatypeSelected is bound to the
    // change event of all of the radio buttons.
    // However, running it manually here takes care of the situation where we've
    // chosen a region option, submitted, and used browser history to go back.
    // Without this, in that situation, a region is picked on the radio button,
    // but lat/lon boxes for a point are displayed.
    if (document.getElementById('pointChoice').checked) {
        datatypeSelected('point');
    } else {
        datatypeSelected('region');
    }
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

function regionSelected(value) {
    if (value == 'BOUNDS') {
        document.getElementById('bounds').style.display = 'block';
    } else {
        document.getElementById('bounds').style.display = 'none';
    }
}

function validateForm() {
    if (!document.getElementById('email').value ||
        !document.getElementById('ref').value) {
        window.alert('You must enter an email address and a job reference.');
        return false;
    }
}

function populateDatasets() {
    var datasetSel = document.getElementById('datasetSelect');
    datasetSel.addEventListener('change', function(e) {
        populateTimes(e.target.value);
    });
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "data?REQUEST=GETDATASETS", true);
    xhr.onload = function(e) {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                var datasets = JSON.parse(xhr.responseText);
                if (datasets.length > 0) {
                    document.getElementById('notLoadedLabel').style.display = 'none';
                    document.getElementById('subsetSelection').style.display = 'block';
                    for (var i = 0; i < datasets.length; i++) {
                        // There should only be one per array element (we are using arrays to preserve order)
                        for (var id in datasets[i]) {
                            datasetSel.appendChild(new Option(datasets[i][id], id));
                        }
                    }
                    populateTimes(datasetSel[datasetSel.selectedIndex].value);
                }
            } else {
                // Something has gone wrong here...
                console.log("Problem getting list of available datasets.  The server may be down");
            }
        }
    };
    xhr.onerror = function(e) {
        console.error(xhr.statusText);
    };
    xhr.send(null);
}

function populateTimes(datasetId) {
    var timesDiv = document.getElementById('timesDiv');
    timesDiv.innerHTML = 'Loading available times...';
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "data?REQUEST=GETTIMES&DATASET=" + datasetId, true);
    xhr.onload = function(e) {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                var startEndTimes = JSON.parse(xhr.responseText);
                var current = new Date(Date.parse(startEndTimes.starttime));
                current.setDate(1)
                var end = new Date(Date.parse(startEndTimes.endtime));
                end.setDate(1)
                end.setMonth(end.getMonth() + 1)

                // Remove the "Loading times..." label
                timesDiv.innerHTML = '';

                var startLabel = document.createElement('label')
                startLabel.for = 'starttime';
                startLabel.innerHTML = 'Start Date:';
                var startSel = document.createElement('select');
                startSel.name = 'starttime';
                startSel.id = 'starttime';
                var br = document.createElement('br');
                var endLabel = document.createElement('label')
                endLabel.for = 'endtime';
                endLabel.innerHTML = 'End Date:';
                var endSel = document.createElement('select');
                endSel.name = 'endtime';
                endSel.id = 'endtime';

                timesDiv.appendChild(startLabel);
                timesDiv.appendChild(startSel);
                timesDiv.appendChild(br);
                timesDiv.appendChild(endLabel);
                timesDiv.appendChild(endSel);

                // Populate the drop-down lists with all available dates
                while (current < end) {
                    startSel.appendChild(new Option(
                        // current.getDate() + '/'
                        +(current.getMonth() + 1) + '/' + current.getFullYear(), current.toISOString()));

                    endSel.appendChild(new Option(
                        // current.getDate() + '/'
                        +(current.getMonth() + 1) + '/' + current.getFullYear(), current.toISOString()));

                    // current.setDate(current.getDate() + 1);
                    current.setMonth(current.getMonth() + 1);
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
                // No times are available.  The dataset is not loaded on the server
                // This is *probably* because we've only just started up.
                // But it could be indicative of a larger error.
                // Display a "first wait and see, then contact us" message.
                document.getElementById('form').style.display = 'none';
                document.getElementById('not_loaded').style.display = 'block';
            }
        }
    };
    xhr.onerror = function(e) {
        console.error(xhr.statusText);
    };
    xhr.send(null);
}

function populateCountries() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "data?REQUEST=GETCOUNTRIES", true);
    xhr.onload = function(e) {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                var countryLabel2Id = JSON.parse(xhr.responseText);
                var countries = Object.keys(countryLabel2Id);
                countries.sort();
                var countrySel = document.getElementById('regionSelect');

                for (var i = 0; i < countries.length; i++) {
                    countrySel.appendChild(new Option(countries[i], countryLabel2Id[countries[i]]));
                }
            }
        }
    };
    xhr.onerror = function(e) {
        console.error(xhr.statusText);
    };
    xhr.send(null);
}
