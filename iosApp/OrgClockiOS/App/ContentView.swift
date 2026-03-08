import SwiftUI
import OrgClockShared

struct ContentView: View {
    private let facade = IosHostFacade()
    private let coreFlowFacade = IosCoreFlowFacade()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("OrgClock iOS Host (M3)")
                .font(.title3)
                .fontWeight(.semibold)

            Text(facade.bootstrapMessage())
                .font(.body)

            Text(facade.sampleParseSummary())
                .font(.footnote)
                .foregroundStyle(.secondary)

            Divider()

            Text("M4 Core Flow Probe")
                .font(.headline)
            Text(coreFlowFacade.listFilesSummary())
                .font(.footnote)
            Text(coreFlowFacade.verifyDailyReadWriteRoundTrip())
                .font(.footnote)
            Text(coreFlowFacade.listHeadingsSummaryForFirstFile())
                .font(.footnote)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }
}
